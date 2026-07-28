package com.viwa.android.platform

/** Adaptive collapse ticker intervals for kiosk [android.app.Activity] system UI. */
internal object KioskCollapseTickerPolicy {
    /** Interval while panels are expected to stay hidden (main-thread friendly). */
    const val HIDDEN_TICK_MS = 1_000L

    /** Legacy fast poll — kept for reference/tests only; production uses [HIDDEN_TICK_MS]. */
    const val LEGACY_FAST_TICK_MS = 250L
}
