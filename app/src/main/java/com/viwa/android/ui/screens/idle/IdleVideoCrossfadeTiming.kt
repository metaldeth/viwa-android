package com.viwa.android.ui.screens.idle

import androidx.media3.common.C

/** Pure timing helpers for idle video crossfade scheduling (unit-testable). */
internal object IdleVideoCrossfadeTiming {
    fun crossfadeDelayMs(
        durationMs: Long,
        positionMs: Long,
        triggerBeforeEndMs: Long,
    ): Long? {
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) return null
        val remaining = durationMs - positionMs
        return (remaining - triggerBeforeEndMs).coerceAtLeast(0L)
    }

    fun shouldCrossfadeNow(
        durationMs: Long,
        positionMs: Long,
        triggerBeforeEndMs: Long,
        nextPlayerReady: Boolean,
    ): Boolean {
        if (!nextPlayerReady) return false
        val delay = crossfadeDelayMs(durationMs, positionMs, triggerBeforeEndMs) ?: return false
        return delay <= 0L
    }
}
