package com.viwa.android.data.local.outbox

import kotlin.random.Random

object OutboxRetryPolicy {
    const val MAX_ATTEMPTS = 50
    const val ACK_TIMEOUT_MS = 30_000L
    const val WS_ACK_FAILURES_BEFORE_REST = 3
    const val PERIODIC_FLUSH_MS = 30_000L
    const val MAX_BATCH_SIZE = 50
    /** Retain ACKED rows at most this long before periodic purge (ms). */
    const val ACKED_RETENTION_MS = 24 * 60 * 60 * 1000L
    /** Interval between bounded ACKED purges while coordinator is active. */
    const val ACKED_PURGE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val BACKOFF_CAPS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    private val TERMINAL_ERROR_CODES =
        setOf(
            "INVALID_PAYLOAD",
            "NOT_FOUND",
            "IDEMPOTENCY_KEY_MISMATCH",
            "DUPLICATE_MESSAGE_ID",
            "BATCH_PAYLOAD_CONFLICT",
            "VALIDATION_ERROR",
        )

    fun nextRetryDelayMs(attempts: Int, random: Random = Random.Default): Long {
        val index = (attempts - 1).coerceAtLeast(0).coerceAtMost(BACKOFF_CAPS_MS.lastIndex)
        val cap = BACKOFF_CAPS_MS[index]
        if (cap <= 0L) return 0L
        return random.nextLong(0, cap + 1)
    }

    fun isTerminalError(code: String): Boolean = code.uppercase() in TERMINAL_ERROR_CODES

    fun shouldMarkDead(attempts: Int): Boolean = attempts > MAX_ATTEMPTS
}
