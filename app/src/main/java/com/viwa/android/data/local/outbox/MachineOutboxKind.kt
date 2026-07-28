package com.viwa.android.data.local.outbox

/** Canonical outbox kind strings — must match backend `machine-outbox.md`. */
enum class MachineOutboxKind(val wireValue: String) {
    SALE_REPORT("sale.report"),
    LOYALTY_WATER_USE("loyalty.water.use"),
    ;

    companion object {
        fun fromWire(value: String): MachineOutboxKind? = entries.firstOrNull { it.wireValue == value }
    }
}
