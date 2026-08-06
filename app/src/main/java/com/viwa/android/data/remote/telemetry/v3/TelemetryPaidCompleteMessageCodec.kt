package com.viwa.android.data.remote.telemetry.v3

import com.viwa.android.domain.telemetry.PaidCompleteSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TelemetryPaidCompleteMessageCodec {
    const val WIRE_TYPE = "telemetry.paid.complete"

    fun encodePayload(paid: PaidCompleteSnapshot): JsonObject =
        buildJsonObject {
            put("transactionId", paid.transactionId)
            put("requestUuid", paid.requestUuid)
            put("occurredAt", paid.occurredAt)
            put("productId", paid.productId)
            put("productNameSnapshot", paid.productNameSnapshot)
            put("volumeMl", paid.volumeMl)
            put("strength", paid.strength)
            put("strengthRatio", paid.strengthRatio)
            put("syrupMlActual", paid.syrupMlActual)
            put("amountKopecks", paid.amountKopecks)
            put("payMethod", paid.payMethod)
            paid.recipeDrinkVolumeMl?.let { put("recipeDrinkVolumeMl", it) }
            paid.recipeWaterMl?.let { put("recipeWaterMl", it) }
            paid.recipeProductMl?.let { put("recipeProductMl", it) }
            paid.conversionFactor?.let { put("conversionFactor", it) }
        }
}
