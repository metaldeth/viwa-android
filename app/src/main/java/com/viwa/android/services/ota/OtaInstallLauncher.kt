package com.viwa.android.services.ota

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

sealed class OtaInstallLaunchResult {
    data object PackageInstallerSessionStarted : OtaInstallLaunchResult()

    data object ActionViewFallbackStarted : OtaInstallLaunchResult()

    data class Failed(val reason: String) : OtaInstallLaunchResult()
}

/**
 * PackageInstaller session first (interactive on non-device-owner via confirmation UI).
 * [OtaPlatformInstallCapability.canSilentInstall] enables silent session commit on device owner.
 * ACTION_VIEW is fallback only when session create/write/commit fails.
 */
@Singleton
class OtaInstallLauncher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val platformCapability: OtaPlatformInstallCapability,
) {
    fun launchInstall(apkFile: File): OtaInstallLaunchResult {
        if (!apkFile.isFile) return OtaInstallLaunchResult.Failed("APK missing")
        if (platformCapability.canUsePackageInstallerSession()) {
            when (val sessionResult = launchPackageInstallerSession(apkFile)) {
                is OtaInstallLaunchResult.Failed -> {
                    Timber.tag(TAG).w("PackageInstaller session failed, falling back to ACTION_VIEW")
                    return launchActionView(apkFile)
                }
                else -> return sessionResult
            }
        }
        return launchActionView(apkFile)
    }

    private fun launchPackageInstallerSession(apkFile: File): OtaInstallLaunchResult {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            if (!OtaInstallSessionSupport.hasSpaceForStaging(context.filesDir.usableSpace, apkFile.length())) {
                return OtaInstallLaunchResult.Failed("Недостаточно места для установки")
            }
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                }
            sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    FileInputStream(apkFile).use { input ->
                        session.openWrite("ota-base.apk", 0, apkFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(context, OtaInstallResultReceiver::class.java)
                    val flags =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                    val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    session.commit(pendingIntent.intentSender)
                }
                OtaInstallLaunchResult.PackageInstallerSessionStarted
            } catch (error: Exception) {
                OtaInstallSessionSupport.abandonSessionSafe(installer, sessionId)
                throw error
            }
        } catch (error: IOException) {
            if (sessionId >= 0) {
                OtaInstallSessionSupport.abandonSessionSafe(installer, sessionId)
            }
            Timber.tag(TAG).w(error, "PackageInstaller session IO failed")
            OtaInstallLaunchResult.Failed(error.message ?: "Ошибка записи APK для установки")
        } catch (error: Exception) {
            if (sessionId >= 0) {
                OtaInstallSessionSupport.abandonSessionSafe(installer, sessionId)
            }
            Timber.tag(TAG).w(error, "PackageInstaller session failed")
            OtaInstallLaunchResult.Failed(error.message ?: "PackageInstaller session failed")
        }
    }

    private fun launchActionView(apkFile: File): OtaInstallLaunchResult =
        runCatching {
            val uri: Uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(installIntent)
            OtaInstallLaunchResult.ActionViewFallbackStarted
        }.getOrElse {
            OtaInstallLaunchResult.Failed(it.message ?: "Install launch failed")
        }

    companion object {
        private const val TAG = "OtaInstallLauncher"
    }
}
