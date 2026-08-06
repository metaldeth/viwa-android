package com.viwa.android.data.remote.telemetry.mvp.cells

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CellsContentReportAckResult(
    val ok: Boolean,
    val applied: Int,
)

object CellsContentReportAckSemantics {
    fun parseAck(payload: JsonObject): Result<CellsContentReportAckResult> {
        val ok = payload["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val applied = payload["applied"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (!ok || applied <= 0) {
            return Result.failure(
                IllegalStateException("Сервер не применил изменение (ok=$ok, applied=$applied)"),
            )
        }
        return Result.success(CellsContentReportAckResult(ok = ok, applied = applied))
    }

    fun isContentReportAckPayload(payload: JsonObject): Boolean = payload.containsKey("applied")
}
