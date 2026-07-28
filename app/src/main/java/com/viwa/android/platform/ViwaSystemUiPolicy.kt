package com.viwa.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * System-level immersive policy via [Settings.Global] `policy_control`.
 *
 * Works when the app is a **priv-app on the firmware image** (whitelist in
 * `/system/etc/permissions/privapp-permissions-*.xml`) or after a one-time
 * `adb shell pm grant … WRITE_SECURE_SETTINGS`. Falls back to [ViwaKioskSystemUi]
 * when the permission is not granted.
 */
object ViwaSystemUiPolicy {
    const val POLICY_CONTROL_KEY: String = "policy_control"

    /** Value for customer kiosk: hide status + navigation bars for this package. */
    fun customerKioskPolicyValue(packageName: String): String = "immersive.full=$packageName"

    fun canManagePolicy(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun applyCustomerKioskPolicy(context: Context): Boolean {
        if (!canManagePolicy(context)) return false
        return runCatching {
            Settings.Global.putString(
                context.contentResolver,
                POLICY_CONTROL_KEY,
                customerKioskPolicyValue(context.packageName),
            )
        }.isSuccess
    }

    fun clearPolicy(context: Context): Boolean {
        if (!canManagePolicy(context)) return false
        return runCatching {
            Settings.Global.putString(context.contentResolver, POLICY_CONTROL_KEY, null)
        }.isSuccess
    }

    fun readCurrentPolicy(context: Context): String? =
        runCatching {
            Settings.Global.getString(context.contentResolver, POLICY_CONTROL_KEY)
        }.getOrNull()
}
