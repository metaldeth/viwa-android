package com.viwa.android.ui.screens.idle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleVideoCrossfadeTimingTest {
    @Test
    fun `crossfade delay subtracts trigger window from remaining duration`() {
        assertEquals(2_500L, IdleVideoCrossfadeTiming.crossfadeDelayMs(10_000L, 6_000L, 1_500L))
        assertEquals(0L, IdleVideoCrossfadeTiming.crossfadeDelayMs(10_000L, 9_000L, 1_500L))
    }

    @Test
    fun `crossfade delay returns null for unknown duration`() {
        assertNull(IdleVideoCrossfadeTiming.crossfadeDelayMs(-1L, 0L, 1_500L))
        assertNull(IdleVideoCrossfadeTiming.crossfadeDelayMs(0L, 0L, 1_500L))
    }

    @Test
    fun `should crossfade only when next player ready and window reached`() {
        assertTrue(
            IdleVideoCrossfadeTiming.shouldCrossfadeNow(
                durationMs = 10_000L,
                positionMs = 9_000L,
                triggerBeforeEndMs = 1_500L,
                nextPlayerReady = true,
            ),
        )
        assertFalse(
            IdleVideoCrossfadeTiming.shouldCrossfadeNow(
                durationMs = 10_000L,
                positionMs = 5_000L,
                triggerBeforeEndMs = 1_500L,
                nextPlayerReady = true,
            ),
        )
        assertFalse(
            IdleVideoCrossfadeTiming.shouldCrossfadeNow(
                durationMs = 10_000L,
                positionMs = 9_000L,
                triggerBeforeEndMs = 1_500L,
                nextPlayerReady = false,
            ),
        )
    }
}
