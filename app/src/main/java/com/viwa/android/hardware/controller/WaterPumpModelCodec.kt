package com.viwa.android.hardware.controller

/**
 * Коэффициент помпы воды / газировки (прошивка Vita Flow TST 2026-08-24).
 *
 * TX 0xBB: `buff_rx[1]=koef_w100`, `buff_rx[3]=skoef_w100`.
 * RX 0x88: `[koef_w100, koef_w100, 0x00, skoef_w100, skoef_w100]`.
 */
data class WaterPumpModel(
    val waterTenths: Int,
    val sodaTenths: Int,
)

object WaterPumpModelCodec {
    const val DEFAULT_WATER_TENTHS = 180
    /** Firmware comments 3.0 → 300, but `unsigned char` max is 255. */
    const val DEFAULT_SODA_TENTHS = 255

    fun parseAnswer(payload: ByteArray): WaterPumpModel {
        val water = payload.getOrNull(0)?.toInt()?.and(0xff) ?: DEFAULT_WATER_TENTHS
        val soda =
            if (payload.size >= 4) {
                payload[3].toInt() and 0xff
            } else {
                water
            }
        return WaterPumpModel(waterTenths = water, sodaTenths = soda)
    }

    fun encodeWrite(waterTenths: Int, sodaTenths: Int): ByteArray {
        val water = waterTenths.coerceIn(1, 255).toByte()
        val soda = sodaTenths.coerceIn(1, 255).toByte()
        return byteArrayOf(water, water, soda, soda, soda)
    }
}
