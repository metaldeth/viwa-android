package com.viwa.android.data.local.entitlement

/** Append-only offline pour ledger states (Phase 3). */
enum class OfflineUsageLedgerState {
    RESERVED,
    POURING,
    FINALIZED,
    ENQUEUED,
    ACKED,
    REJECTED,
    CONFLICT,
    ;

    companion object {
        fun fromWire(value: String): OfflineUsageLedgerState? = entries.firstOrNull { it.name == value }
    }
}
