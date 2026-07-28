package com.viwa.android.data.remote.telemetry.v3

import com.viwa.android.domain.telemetry.PourEventSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TelemetryPourMessageCodec {
    const val WIRE_TYPE = "telemetry.pour.report"

    fun encodePayload(pour: PourEventSnapshot): JsonObject =
        buildJsonObject {
            put("clientId", pour.clientId)
            put("requestUuid", pour.requestUuid)
            put("pouredAt", pour.pouredAt)
            put("pourKind", pour.pourKind)
            put("volumeMl", pour.volumeMl)
            pour.plainWaterType?.let { put("plainWaterType", it) }
            pour.productId?.let { put("productId", it) }
            pour.productNameSnapshot?.let { put("productNameSnapshot", it) }
            pour.strength?.let { put("strength", it) }
            pour.strengthRatio?.let { put("strengthRatio", it) }
            pour.syrupMlActual?.let { put("syrupMlActual", it) }
        }
}
