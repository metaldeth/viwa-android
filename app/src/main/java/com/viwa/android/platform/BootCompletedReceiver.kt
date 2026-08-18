package com.viwa.android.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.viwa.android.ui.MainActivity
import timber.log.Timber

/** Starts the kiosk UI after device reboot (and OEM quick-boot). */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!BOOT_ACTIONS.contains(action)) return
        Timber.i("BootCompletedReceiver: launching Viwa action=%s", action)
        val launch =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                setAction(Intent.ACTION_MAIN)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        runCatching { context.startActivity(launch) }
            .onFailure { Timber.e(it, "BootCompletedReceiver: startActivity failed") }
    }

    companion object {
        private val BOOT_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                ACTION_QUICKBOOT_POWERON,
            )

        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
