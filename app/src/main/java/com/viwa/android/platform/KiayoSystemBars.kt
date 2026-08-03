package com.viwa.android.platform

import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Kiayo / Rockchip K3568 vendor API for system navigation bar (`com.kiayo.externservice`). */
object KiayoSystemBars {
    const val PACKAGE_EXTERN_SERVICE = "com.kiayo.externservice"
    const val ACTION_HIDE_NAVIGATION_BAR = "com.kiayo.hide.navigationBar"
    const val ACTION_SHOW_NAVIGATION_BAR = "com.kiayo.show.navigationBar"
    private const val PERSIST_NAV_BAR_PROP = "persist.kiayo.status.naviBar"
    private const val NAV_BAR_HIDDEN = "0"
    private const val NAV_BAR_SHOWN = "1"

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PACKAGE_EXTERN_SERVICE, 0)
            true
        }.getOrDefault(false)

    fun hideNavigationBar(context: Context) {
        trySetPersistNavBarState(NAV_BAR_HIDDEN)
        sendNavBarBroadcast(context, ACTION_HIDE_NAVIGATION_BAR, "hide")
    }

    fun showNavigationBar(context: Context) {
        trySetPersistNavBarState(NAV_BAR_SHOWN)
        sendNavBarBroadcast(context, ACTION_SHOW_NAVIGATION_BAR, "show")
    }

    /**
     * Best-effort OEM persist sync. Factory ADB / [provision-viwa-kiosk.ps1] remains canonical;
     * this may succeed on priv-app / shell-capable builds and is safe to no-op otherwise.
     */
    private fun trySetPersistNavBarState(value: String) {
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val set =
                clazz.getMethod(
                    "set",
                    String::class.java,
                    String::class.java,
                )
            set.invoke(null, PERSIST_NAV_BAR_PROP, value)
        }.onFailure { e ->
            Timber.d(e, "Kiayo %s via SystemProperties not applied", PERSIST_NAV_BAR_PROP)
        }
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
            Timber.w(e, "Kiayo %s navigation bar failed (will retry on next kiosk apply)", label)
        }
    }
}
