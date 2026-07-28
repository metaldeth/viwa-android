package com.viwa.android.data.local.outbox

enum class MachineOutboxStatus {
    PENDING,
    IN_FLIGHT,
    ACKED,
    REJECTED,
    DEAD,
    ;

    companion object {
        fun fromStored(value: String): MachineOutboxStatus? = entries.firstOrNull { it.name == value }
    }
}
