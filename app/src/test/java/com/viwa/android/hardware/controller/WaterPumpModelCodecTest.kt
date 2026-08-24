package com.viwa.android.hardware.controller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaterPumpModelCodecTest {
    @Test
    fun parseAnswer_readsWaterAndSodaFromTstLayout() {
        val parsed =
            WaterPumpModelCodec.parseAnswer(
                byteArrayOf(180.toByte(), 180.toByte(), 0, 200.toByte(), 200.toByte()),
            )
        assertEquals(180, parsed.waterTenths)
        assertEquals(200, parsed.sodaTenths)
    }

    @Test
    fun parseAnswer_legacyFiveWaterBytes_keepsSodaEqualToWater() {
        val parsed = WaterPumpModelCodec.parseAnswer(ByteArray(5) { 180.toByte() })
        assertEquals(180, parsed.waterTenths)
        assertEquals(180, parsed.sodaTenths)
    }

    @Test
    fun encodeWrite_putsSodaInThirdBodyByte() {
        val body = WaterPumpModelCodec.encodeWrite(waterTenths = 180, sodaTenths = 200)
        assertArrayEquals(
            byteArrayOf(180.toByte(), 180.toByte(), 200.toByte(), 200.toByte(), 200.toByte()),
            body,
        )
    }
}
