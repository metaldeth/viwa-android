package com.viwa.android.domain.model.customer

import com.viwa.android.domain.telemetry.PlainWaterEntitlement

/** D0 WaterPourByTouch start/stop frame builders (test seam for sel-byte gating). */
object WaterPourByTouchPayload {
    val stopBody: ByteArray = byteArrayOf(0, 0, 0, 0, 0)

    fun selByteForPour(
        flowWaterPourType: FlowWaterPourType,
        subscriptionActive: Boolean,
    ): Int = PlainWaterEntitlement.effectivePourType(flowWaterPourType, subscriptionActive).selByte

    fun startPayload(selByte: Int): ByteArray {
        val sel = selByte.coerceIn(0, 2)
        return byteArrayOf(1, sel.toByte(), 1, sel.toByte(), 0)
    }

    fun startPayload(
        flowWaterPourType: FlowWaterPourType,
        subscriptionActive: Boolean,
    ): ByteArray = startPayload(selByteForPour(flowWaterPourType, subscriptionActive))
}
