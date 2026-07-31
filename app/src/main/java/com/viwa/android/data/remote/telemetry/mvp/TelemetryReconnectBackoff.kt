package com.viwa.android.data.remote.telemetry.mvp

import kotlin.random.Random

/** Reconnect delay schedule with full jitter (architecture ws-offline-resilience). */
object TelemetryReconnectBackoff {
    val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    const val SUPERSEDE_BACKOFF_MS = 60_000L
    const val MIN_RECONNECT_DELAY_MS = 250L

    fun delayMs(
        attempt: Int,
        supersededFlatBackoff: Boolean = false,
        random: Random = Random.Default,
    ): Long {
        if (supersededFlatBackoff) {
            return SUPERSEDE_BACKOFF_MS
        }
        val index = attempt.coerceIn(0, DELAYS_MS.lastIndex)
        val cap = DELAYS_MS[index]
        if (cap <= 0L) return 0L
        // Jitter with a small floor prevents a tight auth/token retry loop.
        return random.nextLong(MIN_RECONNECT_DELAY_MS.coerceAtMost(cap), cap + 1)
    }
}
