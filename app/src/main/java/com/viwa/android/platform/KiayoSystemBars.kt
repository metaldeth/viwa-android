package com.viwa.android.platform

import android.content.Context
import android.content.Intent
import android.os.Process
import timber.log.Timber

/**
 * Kiayo / Rockchip K3568 hooks implemented in `system_server`.
 *
 * The broadcasts are protected and persistent properties are system-only. A regular `/data/app`
 * build must rely on factory provisioning; these calls are reserved for a platform-installed Viwa.
 */
object KiayoSystemBars {
    const val ACTION_HIDE_NAVIGATION_BAR = "com.kiayo.hide.navigationBar"
    const val ACTION_SHOW_NAVIGATION_BAR = "com.kiayo.show.navigationBar"
    private const val PERSIST_NAV_BAR_PROP = "persist.kiayo.status.naviBar"
    private const val NAV_BAR_HIDDEN = "0"
    private const val NAV_BAR_SHOWN = "1"

    fun isAvailable(): Boolean = Process.myUid() == Process.SYSTEM_UID

    fun hideNavigationBar(context: Context) {
        if (!isAvailable()) return
        trySetPersistNavBarState(NAV_BAR_HIDDEN)
        sendNavBarBroadcast(context, ACTION_HIDE_NAVIGATION_BAR, "hide")
    }

    fun showNavigationBar(context: Context) {
        if (!isAvailable()) return
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
        runCatching {
            context.sendBroadcast(Intent(action))
        }.onFailure { e ->
            Timber.w(e, "Kiayo %s navigation bar failed (will retry on next kiosk apply)", label)
        }
    }
}
