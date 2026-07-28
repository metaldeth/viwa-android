package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.sales.PendingSale
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TelemetrySalesMessageCodecTest {
    @Test
    fun `encodeSaleReportPayload contains required server keys`() {
        // given
        val sale =
            PendingSale(
                saleId = "11111111-2222-3333-4444-555555555555",
                soldAt = "2026-07-20T12:34:56.789Z",
                drinkId = 20,
                volumeMl = 200,
                amountRub = 150.0,
                payMethod = "CARD",
                concentrationRatio = 1.1,
            )

        // when
        val payload = TelemetrySalesMessageCodec.encodeSaleReportPayload(sale)

        // then
        assertEquals("11111111-2222-3333-4444-555555555555", payload["saleId"]!!.jsonPrimitive.content)
        assertEquals("2026-07-20T12:34:56.789Z", payload["soldAt"]!!.jsonPrimitive.content)
        assertEquals(20, payload["drinkId"]!!.jsonPrimitive.int)
        assertEquals(200, payload["volumeMl"]!!.jsonPrimitive.int)
        assertEquals(150.0, payload["amountRub"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("CARD", payload["payMethod"]!!.jsonPrimitive.content)
        assertEquals(1.1, payload["concentrationRatio"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertNotNull(payload["saleId"])
        assertNotNull(payload["soldAt"])
        assertNotNull(payload["drinkId"])
        assertNotNull(payload["volumeMl"])
        assertNotNull(payload["amountRub"])
        assertNotNull(payload["payMethod"])
        assertNotNull(payload["concentrationRatio"])
    }

    @Test
    fun `encodeSaleReportPayload omits concentrationRatio when null`() {
        // given
        val sale =
            PendingSale(
                saleId = "11111111-2222-3333-4444-555555555555",
                soldAt = "2026-07-20T12:34:56.789Z",
                drinkId = 20,
                volumeMl = 200,
                amountRub = 150.0,
                payMethod = "CARD",
            )

        // when
        val payload = TelemetrySalesMessageCodec.encodeSaleReportPayload(sale)

        // then
        org.junit.Assert.assertNull(payload["concentrationRatio"])
    }
}
