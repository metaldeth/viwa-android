package com.viwa.android.data.remote.telemetry.mvp

import kotlin.random.Random

/** Reconnect delay schedule with full jitter (architecture ws-offline-resilience). */
object TelemetryReconnectBackoff {
    val DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    const val SUPERSEDE_BACKOFF_MS = 60_000L

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
        // Full jitter: uniform in [0, cap]
        return random.nextLong(0, cap + 1)
    }
}
