package com.viwa.android.platform

import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Kiayo / Rockchip K3568 vendor API for system navigation bar (`com.kiayo.externservice`). */
object KiayoSystemBars {
    const val PACKAGE_EXTERN_SERVICE = "com.kiayo.externservice"
    const val ACTION_HIDE_NAVIGATION_BAR = "com.kiayo.hide.navigationBar"
    const val ACTION_SHOW_NAVIGATION_BAR = "com.kiayo.show.navigationBar"

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PACKAGE_EXTERN_SERVICE, 0)
            true
        }.getOrDefault(false)

    fun hideNavigationBar(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            context.sendBroadcast(Intent(ACTION_HIDE_NAVIGATION_BAR))
        }.onFailure { e ->
            Timber.w(e, "Kiayo hide navigation bar failed")
        }
    }

    fun showNavigationBar(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            context.sendBroadcast(Intent(ACTION_SHOW_NAVIGATION_BAR))
        }.onFailure { e ->
            Timber.w(e, "Kiayo show navigation bar failed")
        }
    }
}
