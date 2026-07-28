package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.sales.PendingSale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TelemetrySalesMessageCodec {
    fun encodeSaleReportPayload(sale: PendingSale): JsonObject =
        buildJsonObject {
            put("saleId", sale.saleId)
            put("soldAt", sale.soldAt)
            put("drinkId", sale.drinkId)
            put("volumeMl", sale.volumeMl)
            put("amountRub", sale.amountRub)
            put("payMethod", sale.payMethod)
            sale.concentrationRatio?.let { put("concentrationRatio", it) }
        }
}
