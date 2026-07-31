package com.viwa.android.domain.model.customer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaterPourByTouchPayloadTest {
    @Test
    fun startPayload_selBytes_matchFilteredColdSparkling() {
        assertArrayEquals(
            byteArrayOf(1, 0, 1, 0, 0),
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Filtered, subscriptionActive = false),
        )
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 1, 0),
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Cold, subscriptionActive = true),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 1, 2, 0),
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Sparkling, subscriptionActive = true),
        )
    }

    @Test
    fun startPayload_coercesPremiumToFilteredWithoutActiveSubscription() {
        assertEquals(0, WaterPourByTouchPayload.selByteForPour(FlowWaterPourType.Cold, subscriptionActive = false))
        assertEquals(0, WaterPourByTouchPayload.selByteForPour(FlowWaterPourType.Sparkling, subscriptionActive = false))
        assertArrayEquals(
            byteArrayOf(1, 0, 1, 0, 0),
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Sparkling, subscriptionActive = false),
        )
    }
}
