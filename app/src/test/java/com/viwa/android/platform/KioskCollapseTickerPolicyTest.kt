package com.viwa.android.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskCollapseTickerPolicyTest {
    @Test
    fun `hidden tick uses main-thread safe fallback interval`() {
        assertEquals(250L, KioskCollapseTickerPolicy.HIDDEN_TICK_MS)
    }
}
