package com.viwa.android.data.remote.telemetry.v3



import com.viwa.android.domain.model.customer.DrinkConcentration

import com.viwa.android.domain.model.customer.DrinkDosage

import com.viwa.android.domain.telemetry.DispenseTelemetryFactory

import com.viwa.android.domain.telemetry.PlainWaterType

import com.viwa.android.domain.telemetry.PourKind

import com.viwa.android.domain.telemetry.TelemetryPourMath
import com.viwa.android.domain.telemetry.WaterUsageReportSnapshot

import kotlinx.serialization.json.int

import kotlinx.serialization.json.jsonPrimitive

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull

import org.junit.Test



class TelemetryPourMessageCodecTest {

    @Test
    fun `encodePayload contains optional recipe fields when present`() {
        val pour =
            DispenseTelemetryFactory.flavoredPourEvent(
                requestUuid = "880e8400-e29b-41d4-a716-446655440099",
                volumeMl = 300,
                productId = "prod-uuid",
                productNameSnapshot = "Cola",
                concentration = DrinkConcentration.Standard,
                dosage = DrinkDosage(conversionFactor = 5.5, drinkVolume = 300, product = 30.0, water = 270.0),
                clientId = "client-1",
            )
        val payload = TelemetryPourMessageCodec.encodePayload(pour)
        assertEquals(300, payload["recipeDrinkVolumeMl"]!!.jsonPrimitive.int)
        assertEquals(270.0, payload["recipeWaterMl"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(30.0, payload["recipeProductMl"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(5.5, payload["conversionFactor"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `encodePayload omits recipe fields for plain hold`() {
        val pour =
            DispenseTelemetryFactory.plainPourEvent(
                requestUuid = "req-plain",
                volumeMl = 142,
                clientId = "client-1",
                plainWaterType = PlainWaterType.COLD,
            )
        val payload = TelemetryPourMessageCodec.encodePayload(pour)
        assertFalse(payload.containsKey("recipeDrinkVolumeMl"))
        assertFalse(payload.containsKey("conversionFactor"))
    }

    @Test

    fun `encodePayload contains subscription flavored keys`() {

        val pour =

            DispenseTelemetryFactory.flavoredPourEvent(

                requestUuid = "880e8400-e29b-41d4-a716-446655440099",

                volumeMl = 300,

                productId = "prod-uuid",

                productNameSnapshot = "Cola",

                concentration = DrinkConcentration.Standard,

                dosage = DrinkDosage(conversionFactor = 0.5, drinkVolume = 300, product = 30.0, water = 270.0),

                clientId = "client-1",

            )

        val payload = TelemetryPourMessageCodec.encodePayload(pour)

        assertEquals("telemetry.pour.report", TelemetryPourMessageCodec.WIRE_TYPE)

        assertEquals("880e8400-e29b-41d4-a716-446655440099", payload["requestUuid"]!!.jsonPrimitive.content)

        assertEquals(300, payload["volumeMl"]!!.jsonPrimitive.int)

        assertEquals("FLAVORED", payload["pourKind"]!!.jsonPrimitive.content)

        assertEquals("prod-uuid", payload["productId"]!!.jsonPrimitive.content)

        assertEquals("Cola", payload["productNameSnapshot"]!!.jsonPrimitive.content)

        assertEquals("STANDARD", payload["strength"]!!.jsonPrimitive.content)

        assertEquals(30, payload["syrupMlActual"]!!.jsonPrimitive.int)

        assertFalse(payload.containsKey("grantId"))

        assertFalse(payload.containsKey("transactionId"))

    }



    @Test
    fun `encodePayload plain hold includes SPARKLING wire type`() {
        val pour =
            DispenseTelemetryFactory.plainPourEvent(
                requestUuid = "req-sparkling",
                volumeMl = 120,
                clientId = "client-1",
                plainWaterType = PlainWaterType.SPARKLING,
            )
        val payload = TelemetryPourMessageCodec.encodePayload(pour)
        assertEquals("SPARKLING", payload["plainWaterType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodePayload anonymous plain hold allows null clientId`() {
        val pour =
            DispenseTelemetryFactory.plainPourEvent(
                requestUuid = "req-anon",
                volumeMl = 90,
                clientId = null,
                plainWaterType = PlainWaterType.FILTERED,
            )
        val payload = TelemetryPourMessageCodec.encodePayload(pour)
        assertEquals("PLAIN_WATER", payload["pourKind"]!!.jsonPrimitive.content)
        assertNull(payload["clientId"])
    }

    @Test
    fun `encodePayload plain hold omits product`() {
        val pour =
            DispenseTelemetryFactory.plainPourEvent(
                requestUuid = "req-plain",
                volumeMl = 142,
                clientId = "client-1",
                plainWaterType = PlainWaterType.COLD,
            )
        val payload = TelemetryPourMessageCodec.encodePayload(pour)
        assertEquals("PLAIN_WATER", payload["pourKind"]!!.jsonPrimitive.content)
        assertEquals("COLD", payload["plainWaterType"]!!.jsonPrimitive.content)
        assertNull(payload["productId"])
        assertNull(payload["productNameSnapshot"])
    }
}



class TelemetryPaidCompleteMessageCodecTest {

    @Test

    fun `encodePayload is flat atomic paid complete`() {

        val paid =

            DispenseTelemetryFactory.paidComplete(

                transactionId = "tx-1",

                requestUuid = "pour-1",

                volumeMl = 700,

                amountRub = 199.0,

                payMethod = "SBP",

                productId = "prod",

                productNameSnapshot = "Lemon",

                concentration = DrinkConcentration.Strong,

                dosage = DrinkDosage(0.5, 300, 30.0, 270.0),

            )

        val payload = TelemetryPaidCompleteMessageCodec.encodePayload(paid)

        assertEquals("telemetry.paid.complete", TelemetryPaidCompleteMessageCodec.WIRE_TYPE)

        assertEquals("tx-1", payload["transactionId"]!!.jsonPrimitive.content)

        assertEquals("pour-1", payload["requestUuid"]!!.jsonPrimitive.content)

        assertEquals(700, payload["volumeMl"]!!.jsonPrimitive.int)

        assertEquals(19900, payload["amountKopecks"]!!.jsonPrimitive.int)

        assertEquals(77, payload["syrupMlActual"]!!.jsonPrimitive.int)

        assertEquals(300, payload["recipeDrinkVolumeMl"]!!.jsonPrimitive.int)

        assertEquals(270.0, payload["recipeWaterMl"]!!.jsonPrimitive.content.toDouble(), 0.001)

        assertEquals(30.0, payload["recipeProductMl"]!!.jsonPrimitive.content.toDouble(), 0.001)

        assertEquals(0.5, payload["conversionFactor"]!!.jsonPrimitive.content.toDouble(), 0.001)

        assertEquals("FILTERED", payload["plainWaterType"]!!.jsonPrimitive.content)

        assertFalse(payload.containsKey("pour"))

        assertFalse(payload.containsKey("soldAt"))

        assertFalse(payload.containsKey("amountRub"))

    }

}



class TelemetryWaterUsageMessageCodecTest {
    @Test
    fun `encodePayload contains absolute total and reportedAt`() {
        val payload =
            TelemetryWaterUsageMessageCodec.encodePayload(
                WaterUsageReportSnapshot(totalMl = 4321, reportedAt = "2026-08-05T12:34:56.789Z"),
            )
        assertEquals("machine.water.usage.report", TelemetryWaterUsageMessageCodec.WIRE_TYPE)
        assertEquals(4321, payload["totalMl"]!!.jsonPrimitive.int)
        assertEquals("2026-08-05T12:34:56.789Z", payload["reportedAt"]!!.jsonPrimitive.content)
    }
}

class TelemetryPourMathTest {
    @Test
    fun `syrup ml matches canonical 300 base table`() {
        val dosage = 30.0

        assertEquals(27, TelemetryPourMath.syrupMlActual(dosage, 300, 300, 0.9))

        assertEquals(30, TelemetryPourMath.syrupMlActual(dosage, 300, 300, 1.0))

        assertEquals(33, TelemetryPourMath.syrupMlActual(dosage, 300, 300, 1.1))

        assertEquals(63, TelemetryPourMath.syrupMlActual(dosage, 300, 700, 0.9))

        assertEquals(70, TelemetryPourMath.syrupMlActual(dosage, 300, 700, 1.0))

        assertEquals(77, TelemetryPourMath.syrupMlActual(dosage, 300, 700, 1.1))
    }
}


