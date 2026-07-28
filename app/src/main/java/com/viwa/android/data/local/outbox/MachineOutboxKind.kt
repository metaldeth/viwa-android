package com.viwa.android.data.local.outbox

/** Canonical outbox kind strings — must match `telemetry-v3-ingest.md` / `machine-outbox.md`. */
enum class MachineOutboxKind(val wireValue: String) {
    TELEMETRY_POUR_REPORT("telemetry.pour.report"),
    TELEMETRY_PAID_COMPLETE("telemetry.paid.complete"),
    ;

    companion object {
        fun fromWire(value: String): MachineOutboxKind? = entries.firstOrNull { it.wireValue == value }
    }
}
