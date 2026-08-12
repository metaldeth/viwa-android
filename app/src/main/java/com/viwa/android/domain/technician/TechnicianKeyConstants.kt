package com.viwa.android.domain.technician

/** Must match `viwa-telemetry/.../technician-key.util.ts`. */
object TechnicianKeyConstants {
    const val KEY_PREFIX = "KEY-"
    const val LEGACY_PREFIX = "EMP:"
    const val BODY_LENGTH = 20
    const val KEY_PATTERN = "^KEY-[0-9A-HJKMNP-TV-Z]{${BODY_LENGTH}}$"

    const val SESSION_TTL_SECONDS = 900
    const val SESSION_TTL_MS = SESSION_TTL_SECONDS * 1000L
    const val ALLOWLIST_SYNC_INTERVAL_MS = 300 * 1000L
    const val ALLOWLIST_SYNC_BACKOFF_BASE_MS = 5_000L
    const val ALLOWLIST_SYNC_BACKOFF_MAX_MS = 5 * 60 * 1000L
    const val MAX_AUDIT_BATCH_SIZE = 50
    /** Fail-closed cap for unsynced audit rows; never silently drop pending records. */
    const val MAX_PENDING_AUDIT_RECORDS = 500
    const val AUDIT_SYNC_INTERVAL_MS = 60_000L
    const val AUDIT_SYNC_BACKOFF_BASE_MS = 5_000L
    const val AUDIT_SYNC_BACKOFF_MAX_MS = 5 * 60 * 1000L
    const val AUDIT_TERMINAL_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    const val SCOPE_SERVICE_MENU = "service.menu"
    const val SCOPE_FIRMWARE_UPDATE = "firmware.update"

    val OFFLINE_SCOPES =
        setOf(
            "service.menu",
            "diagnostics.read",
            "cells.calibrate",
            "cells.replenishment",
        )

    /**
     * Local password `"studio"` session scopes.
     * Includes [SCOPE_FIRMWARE_UPDATE] so OTA install works without KEY-* scan;
     * KEY offline path keeps [OFFLINE_SCOPES] only (firmware.update remains online-only there).
     */
    val STUDIO_SCOPES: Set<String> = OFFLINE_SCOPES + SCOPE_FIRMWARE_UPDATE

    val ONLINE_ONLY_SCOPES =
        setOf(
            "registration.rebind",
            "firmware.update",
            "payments.init",
            "secrets.read",
            "loyalty.admin",
        )

    val ALL_KNOWN_SCOPES: Set<String> = OFFLINE_SCOPES + ONLINE_ONLY_SCOPES
}

enum class TechnicianAuthorizationReason {
    GRANTED,
    KEY_INVALID_FORMAT,
    KEY_NOT_FOUND,
    KEY_REVOKED,
    KEY_EXPIRED,
    KEY_MACHINE_DENIED,
    KEY_SCOPE_DENIED,
    OFFLINE_DISABLED,
    OFFLINE_NO_ALLOWLIST,
    OFFLINE_STALE_ALLOWLIST,
    OFFLINE_CLOCK_UNSAFE,
    OFFLINE_SCOPE_DENIED,
    ONLINE_REQUIRED,
    ONLINE_UNAVAILABLE,
    SERVER_DENIED,
    OFFLINE_POLICY_DISABLED,
    OFFLINE_NO_TRUSTED_SYNC,
    AUDIT_ENQUEUE_FAILED,
    FEATURE_DISABLED,
}
