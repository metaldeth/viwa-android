package com.viwa.android.platform

import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Kiayo / Rockchip K3568 vendor API for system navigation bar (`com.kiayo.externservice`). */
object KiayoSystemBars {
    const val PACKAGE_EXTERN_SERVICE = "com.kiayo.externservice"
    const val ACTION_HIDE_NAVIGATION_BAR = "com.kiayo.hide.navigationBar"
    const val ACTION_SHOW_NAVIGATION_BAR = "com.kiayo.show.navigationBar"

    @Volatile
    private var broadcastDenied = false

    fun isAvailable(context: Context): Boolean =
        !broadcastDenied &&
            runCatching {
                context.packageManager.getPackageInfo(PACKAGE_EXTERN_SERVICE, 0)
                true
            }.getOrDefault(false)

    fun hideNavigationBar(context: Context) {
        sendNavBarBroadcast(context, ACTION_HIDE_NAVIGATION_BAR, "hide")
    }

    fun showNavigationBar(context: Context) {
        sendNavBarBroadcast(context, ACTION_SHOW_NAVIGATION_BAR, "show")
    }

    private fun sendNavBarBroadcast(
        context: Context,
        action: String,
        label: String,
    ) {
        if (!isAvailable(context)) return
        runCatching {
            context.sendBroadcast(Intent(action))
        }.onFailure { e ->
            if (e is SecurityException) {
                broadcastDenied = true
            }
            Timber.w(e, "Kiayo %s navigation bar failed", label)
        }
    }
}
