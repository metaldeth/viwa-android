package com.viwa.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskCollapseTickerPolicyTest {
    @Test
    fun `hidden tick uses one second interval`() {
        assertEquals(1_000L, KioskCollapseTickerPolicy.HIDDEN_TICK_MS)
        assertTrue(KioskCollapseTickerPolicy.HIDDEN_TICK_MS > KioskCollapseTickerPolicy.LEGACY_FAST_TICK_MS)
    }
}
