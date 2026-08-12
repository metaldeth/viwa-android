package com.viwa.android.services.ota

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaPlatformInstallCapability
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    /** True only when platform privilege is detected (device owner silent install). */
    fun canSilentInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    /**
     * PackageInstaller session is available for interactive install on non-device-owner devices.
     * Session commit is **not** silent unless [canSilentInstall] is true.
     */
    fun canUsePackageInstallerSession(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}
