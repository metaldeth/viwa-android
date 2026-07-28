package com.viwa.android.domain.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class OtaApkVerificationError(message: String) : Exception(message) {
    class PackageNameMismatch(expected: String, actual: String) :
        OtaApkVerificationError("Package mismatch: expected $expected, got $actual")

    class VersionCodeMismatch(expected: Int, actual: Int) :
        OtaApkVerificationError("VersionCode mismatch: expected $expected, got $actual")

    class DowngradeRejected(current: Int, offered: Int) :
        OtaApkVerificationError("Downgrade rejected: current=$current offered=$offered")

    class SigningCertMismatch(expected: String, actual: String) :
        OtaApkVerificationError("Signing certificate mismatch")

    class FileMissing : OtaApkVerificationError("APK file missing")

    class HashMismatch : OtaApkVerificationError("APK SHA-256 mismatch")

    class SizeMismatch : OtaApkVerificationError("APK size mismatch")
}

@Singleton
class OtaApkVerifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    fun readInstalledVersionCode(): Int {
        val info = installedPackageInfo(context.packageName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun verifyDownloadedApk(
        apkFile: File,
        expectedPackageName: String,
        expectedVersionCode: Int,
        expectedSha256: String,
        expectedSizeBytes: Long,
        expectedSigningCertSha256: String,
        allowDowngrade: Boolean = false,
    ): Result<Unit> =
        runCatching {
            if (!apkFile.isFile) throw OtaApkVerificationError.FileMissing()
            if (apkFile.length() != expectedSizeBytes) throw OtaApkVerificationError.SizeMismatch()
            val digest = sha256Hex(apkFile.readBytes())
            if (!digest.equals(expectedSha256, ignoreCase = true)) {
                throw OtaApkVerificationError.HashMismatch()
            }

            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags())
                ?: throw OtaApkVerificationError.FileMissing()
            if (archiveInfo.packageName != expectedPackageName) {
                throw OtaApkVerificationError.PackageNameMismatch(expectedPackageName, archiveInfo.packageName)
            }
            val archiveVersionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    archiveInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    archiveInfo.versionCode
                }
            if (archiveVersionCode != expectedVersionCode) {
                throw OtaApkVerificationError.VersionCodeMismatch(expectedVersionCode, archiveVersionCode)
            }

            val currentVersionCode = readInstalledVersionCode()
            if (!allowDowngrade && expectedVersionCode <= currentVersionCode) {
                throw OtaApkVerificationError.DowngradeRejected(currentVersionCode, expectedVersionCode)
            }

            val certSha256 = readArchiveSigningCertSha256(apkFile)
            if (!certSha256.equals(expectedSigningCertSha256, ignoreCase = true)) {
                throw OtaApkVerificationError.SigningCertMismatch(expectedSigningCertSha256, certSha256)
            }
        }

    private fun installedPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }

    private fun archiveFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun readArchiveSigningCertSha256(apkFile: File): String {
        val info =
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags())
                ?: return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return ""
            val signatures =
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            val first = signatures?.firstOrNull()?.toByteArray() ?: return ""
            return sha256Hex(first)
        }
        @Suppress("DEPRECATION")
        val signature = info.signatures?.firstOrNull()?.toByteArray() ?: return ""
        return sha256Hex(signature)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
