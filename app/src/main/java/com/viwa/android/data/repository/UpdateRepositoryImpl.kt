package com.viwa.android.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.viwa.android.domain.model.AppUpdate
import com.viwa.android.domain.model.UpdateProgress
import com.viwa.android.domain.ota.AppUpdateCoordinator
import com.viwa.android.domain.ota.OtaApkVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface UpdateRepository {
    val progressFlow: SharedFlow<UpdateProgress>
    val coordinatorSnapshot: StateFlow<com.viwa.android.domain.ota.AppUpdateCoordinatorSnapshot>

    suspend fun getCurrentVersion(): String

    suspend fun getCurrentVersionCode(): Int

    suspend fun checkUpdate(): Result<AppUpdate?>

    suspend fun downloadAndInstall(update: AppUpdate): Result<Unit>

    suspend fun checkTelemetryUpdate(): Result<AppUpdate?>

    suspend fun installTelemetryUpdate(requireFirmwareScope: Boolean, hasFirmwareScope: Boolean): Result<Unit>
}

class UpdateRepositoryImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val appUpdateCoordinator: AppUpdateCoordinator,
    private val apkVerifier: OtaApkVerifier,
) : UpdateRepository {
    override val progressFlow: SharedFlow<UpdateProgress> = appUpdateCoordinator.progressFlow
    override val coordinatorSnapshot = appUpdateCoordinator.snapshot

    override suspend fun getCurrentVersion(): String =
        withContext(Dispatchers.IO) {
            val info = installedPackageInfo()
            info.versionName ?: "unknown"
        }

    override suspend fun getCurrentVersionCode(): Int = withContext(Dispatchers.IO) { apkVerifier.readInstalledVersionCode() }

    override suspend fun checkUpdate(): Result<AppUpdate?> = checkTelemetryUpdate()

    override suspend fun checkTelemetryUpdate(): Result<AppUpdate?> =
        withContext(Dispatchers.IO) {
            appUpdateCoordinator.checkForUpdatesManual().map { offer ->
                offer?.toAppUpdate()
            }
        }

    override suspend fun downloadAndInstall(update: AppUpdate): Result<Unit> =
        installTelemetryUpdate(requireFirmwareScope = true, hasFirmwareScope = true)

    override suspend fun installTelemetryUpdate(
        requireFirmwareScope: Boolean,
        hasFirmwareScope: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            appUpdateCoordinator.installOfferedUpdate(requireFirmwareScope, hasFirmwareScope)
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
