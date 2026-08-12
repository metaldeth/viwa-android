package com.viwa.android.services.ota

import android.content.pm.PackageInstaller
import timber.log.Timber

internal object OtaInstallSessionSupport {
    private const val TAG = "OtaInstallLauncher"
    private const val STAGING_MARGIN_BYTES = 16L * 1024L * 1024L

    fun hasSpaceForStaging(usableSpaceBytes: Long, apkBytes: Long): Boolean {
        val required = apkBytes + STAGING_MARGIN_BYTES
        return usableSpaceBytes >= required
    }

    fun abandonSessionSafe(installer: PackageInstaller, sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
            .onFailure { Timber.tag(TAG).w(it, "abandonSession failed for sessionId=%d", sessionId) }
    }
}
