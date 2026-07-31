package com.viwa.android.platform

/** Adaptive collapse ticker intervals for kiosk [android.app.Activity] system UI. */
internal object KioskCollapseTickerPolicy {
    /** Fallback poll; focus/insets callbacks perform immediate re-hide. */
    const val HIDDEN_TICK_MS = 1_000L
}
