package com.viwa.android.domain.offline

/** Offline authorization deny / audit reasons — no PII in log tags. */
enum class OfflineAuthorizationReason {
    GRANTED,
    OFFLINE_NO_GRANT,
    OFFLINE_GRANT_EXPIRED,
    OFFLINE_GRANT_REVOKED,
    OFFLINE_GRANT_TAMPERED,
    OFFLINE_GRANT_MACHINE_MISMATCH,
    OFFLINE_CLOCK_UNSAFE,
    OFFLINE_POUR_LIMIT,
    OFFLINE_VOLUME_LIMIT,
    OFFLINE_DAILY_EXCEEDED,
    OFFLINE_DISABLED,
    OFFLINE_FEATURE_DISABLED,
}

object OfflineEntitlementConstants {
    const val OFFLINE_CLOCK_SKEW_MS = 5 * 60 * 1000L
    const val DELTA_SYNC_INTERVAL_MS = 15 * 60 * 1000L
    const val DELTA_SYNC_BACKOFF_BASE_MS = 5_000L
    const val DELTA_SYNC_BACKOFF_MAX_MS = 5 * 60 * 1000L
    const val STALE_GRANT_HARD_DENY_MS = 72 * 60 * 60 * 1000L
}
