package com.viwa.android.data.remote.telemetry.v3

import com.viwa.android.domain.telemetry.WaterUsageReportSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TelemetryWaterUsageMessageCodec {
    const val WIRE_TYPE = "machine.water.usage.report"

    fun encodePayload(report: WaterUsageReportSnapshot): JsonObject =
        buildJsonObject {
            put("totalMl", report.totalMl)
            put("reportedAt", report.reportedAt)
        }
}
