package com.viwa.android.platform

/** Adaptive collapse ticker intervals for kiosk [android.app.Activity] system UI. */
internal object KioskCollapseTickerPolicy {
    /** Aggressive poll (aligned with snack kiosk); focus/insets callbacks re-hide immediately. */
    const val HIDDEN_TICK_MS = 250L
}
