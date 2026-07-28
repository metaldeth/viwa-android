package com.viwa.android.platform

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Immersive fullscreen for Viwa kiosk (no Device Owner). Ported from shaker snack. */
object ViwaKioskSystemUi {
    private const val LEGACY_IMMERSIVE_FLAGS =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LOW_PROFILE

    fun hideSystemBars(activity: Activity) {
        hideSystemBars(activity.window)
    }

    fun hideSystemBars(window: Window) {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            applyLegacyImmersiveFlags(window.decorView)
        }
    }

    fun legacyImmersiveFlags(): Int = LEGACY_IMMERSIVE_FLAGS

    private fun applyLegacyImmersiveFlags(decorView: View) {
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = LEGACY_IMMERSIVE_FLAGS
    }
}
