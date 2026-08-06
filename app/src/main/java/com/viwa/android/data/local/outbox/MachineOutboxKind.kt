package com.viwa.android.data.local.outbox

/** Canonical outbox kind strings — must match `telemetry-v3-ingest.md` / `machine-outbox.md`. */
enum class MachineOutboxKind(val wireValue: String) {
    TELEMETRY_POUR_REPORT("telemetry.pour.report"),
    TELEMETRY_PAID_COMPLETE("telemetry.paid.complete"),
    MACHINE_WATER_USAGE_REPORT("machine.water.usage.report"),
    CELLS_RECIPE_REPORT("cells.recipe.report"),
    CELLS_RECIPE_COMMAND_ACK("cells.recipe.command.ack"),
    ;

    val drainPriority: Int
        get() =
            when (this) {
                CELLS_RECIPE_REPORT -> 0
                CELLS_RECIPE_COMMAND_ACK -> 1
                else -> 2
            }

    companion object {
        fun fromWire(value: String): MachineOutboxKind? = entries.firstOrNull { it.wireValue == value }
    }
}
