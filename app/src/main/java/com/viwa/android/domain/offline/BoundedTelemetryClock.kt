package com.viwa.android.domain.offline

import android.os.SystemClock
import com.viwa.android.domain.offline.OfflineEntitlementConstants.OFFLINE_CLOCK_SKEW_MS
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded monotonic + server offset clock. Never trusts device wall clock alone.
 */
@Singleton
class BoundedTelemetryClock
@Inject
constructor() {
    private val lock = Any()
    private var serverOffsetMs: Long = 0L
    private var lastTrustedWallMs: Long = 0L
    private var lastMonotonicMs: Long = SystemClock.elapsedRealtime()
    private var clockUnsafe: Boolean = false

    fun updateFromServer(serverTimeUtc: String) {
        val serverMs =
            runCatching {
                Instant.from(DateTimeFormatter.ISO_INSTANT.parse(serverTimeUtc)).toEpochMilli()
            }.getOrNull() ?: return
        synchronized(lock) {
            val wallNow = System.currentTimeMillis()
            val monoNow = SystemClock.elapsedRealtime()
            if (lastTrustedWallMs > 0L && wallNow + OFFLINE_CLOCK_SKEW_MS < lastTrustedWallMs) {
                clockUnsafe = true
            }
            serverOffsetMs = serverMs - wallNow
            lastTrustedWallMs = wallNow
            lastMonotonicMs = monoNow
            if (kotlin.math.abs(serverOffsetMs) <= OFFLINE_CLOCK_SKEW_MS * 2) {
                clockUnsafe = false
            }
        }
    }

    fun trustedNowMs(): Long {
        synchronized(lock) {
            val wallNow = System.currentTimeMillis()
            val monoNow = SystemClock.elapsedRealtime()
            if (lastTrustedWallMs > 0L && wallNow + OFFLINE_CLOCK_SKEW_MS < lastTrustedWallMs) {
                clockUnsafe = true
            }
            lastTrustedWallMs = wallNow
            lastMonotonicMs = monoNow
            return wallNow + serverOffsetMs
        }
    }

    fun isClockUnsafe(): Boolean =
        synchronized(lock) {
            clockUnsafe
        }

    fun markClockUnsafe() {
        synchronized(lock) {
            clockUnsafe = true
        }
    }
}
