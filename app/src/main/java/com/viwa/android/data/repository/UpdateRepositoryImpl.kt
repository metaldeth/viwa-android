package com.viwa.android.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.AppUpdate
import com.viwa.android.domain.model.UpdateProgress
import com.viwa.android.domain.ota.AppUpdateCoordinator
import com.viwa.android.domain.ota.OtaApkVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

interface UpdateRepository {
    val progressFlow: SharedFlow<UpdateProgress>
    val coordinatorSnapshot: StateFlow<com.viwa.android.domain.ota.AppUpdateCoordinatorSnapshot>

    suspend fun getCurrentVersion(): String

    suspend fun getCurrentVersionCode(): Int

    suspend fun getUpdateServerHost(): String

    suspend fun setUpdateServerHost(host: String)

    suspend fun checkUpdate(): Result<AppUpdate?>

    suspend fun downloadAndInstall(update: AppUpdate): Result<Unit>

    suspend fun checkTelemetryUpdate(): Result<AppUpdate?>

    suspend fun installTelemetryUpdate(requireFirmwareScope: Boolean, hasFirmwareScope: Boolean): Result<Unit>

    suspend fun isLegacyFallbackEnabled(): Boolean

    suspend fun setLegacyFallbackEnabled(enabled: Boolean)
}

@Suppress("UNUSED_PARAMETER")
class UpdateRepositoryImpl
@Inject
constructor(
    private val okHttpClient: OkHttpClient,
    private val configRepository: ConfigRepository,
    @ApplicationContext private val context: Context,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val apkVerifier: OtaApkVerifier,
    @AppIoScope appIoScope: CoroutineScope,
) : UpdateRepository {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    override val progressFlow: SharedFlow<UpdateProgress> = appUpdateCoordinator.progressFlow
    override val coordinatorSnapshot = appUpdateCoordinator.snapshot

    override suspend fun getCurrentVersion(): String =
        withContext(Dispatchers.IO) {
            val info = installedPackageInfo()
            info.versionName ?: "unknown"
        }

    override suspend fun getCurrentVersionCode(): Int = withContext(Dispatchers.IO) { apkVerifier.readInstalledVersionCode() }

    override suspend fun getUpdateServerHost(): String =
        configRepository.get(JsonStoreKeys.UPDATE_SERVER_HOST) ?: "http://83.166.246.158:9083"

    override suspend fun setUpdateServerHost(host: String) =
        configRepository.set(JsonStoreKeys.UPDATE_SERVER_HOST, host)

    override suspend fun checkUpdate(): Result<AppUpdate?> =
        withContext(Dispatchers.IO) {
            if (isLegacyFallbackEnabled()) {
                checkLegacyUpdate()
            } else {
                checkTelemetryUpdate()
            }
        }

    override suspend fun checkTelemetryUpdate(): Result<AppUpdate?> =
        withContext(Dispatchers.IO) {
            appUpdateCoordinator.checkForUpdatesManual().map { offer ->
                offer?.toLegacyAppUpdate()
            }
        }

    override suspend fun downloadAndInstall(update: AppUpdate): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (update.telemetryOffer) {
                installTelemetryUpdate(requireFirmwareScope = true, hasFirmwareScope = true)
            } else {
                downloadAndInstallLegacy(update)
            }
        }

    override suspend fun installTelemetryUpdate(
        requireFirmwareScope: Boolean,
        hasFirmwareScope: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            appUpdateCoordinator.installOfferedUpdate(requireFirmwareScope, hasFirmwareScope)
        }

    override suspend fun isLegacyFallbackEnabled(): Boolean = appUpdateCoordinator.isLegacyFallbackEnabled()

    override suspend fun setLegacyFallbackEnabled(enabled: Boolean) {
        appUpdateCoordinator.setLegacyFallbackEnabled(enabled)
    }

    private suspend fun checkLegacyUpdate(): Result<AppUpdate?> =
        withContext(Dispatchers.IO) {
            try {
                val host = getUpdateServerHost().trimEnd('/')
                val response =
                    okHttpClient
                        .newCall(
                            Request.Builder().url("$host/version.json").build(),
                        ).execute()
                if (!response.isSuccessful) return@withContext Result.success(null)
                val body = response.body?.string() ?: return@withContext Result.success(null)
                val update = json.decodeFromString<AppUpdate>(body)
                val currentVersion = getCurrentVersion()
                Result.success(if (update.version == currentVersion) null else update.copy(telemetryOffer = false))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    private suspend fun downloadAndInstallLegacy(update: AppUpdate): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response =
                    okHttpClient
                        .newCall(Request.Builder().url(update.url).build())
                        .execute()
                val body = response.body ?: error("Empty APK response")
                val totalBytes = body.contentLength()

                val apkFile = File(context.filesDir, "update.apk")
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            appUpdateCoordinator.emitProgress(UpdateProgress(downloaded, totalBytes))
                        }
                    }
                }

                val apkUri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile,
                    )
                val installIntent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                    }
                context.startActivity(installIntent)
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    private fun installedPackageInfo() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
