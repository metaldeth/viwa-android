package com.viwa.android.ui.screens.idle

import androidx.media3.common.C
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
        assertNull(IdleVideoCrossfadeTiming.crossfadeDelayMs(C.TIME_UNSET, 0L, 1_500L))
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

    @Test
    fun `preload delay starts six seconds before crossfade window`() {
        assertEquals(
            0L,
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 30_000L,
                positionMs = 22_500L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertEquals(
            2_000L,
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 30_000L,
                positionMs = 20_500L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
    }

    @Test
    fun `should preload when preload window reached`() {
        assertTrue(
            IdleVideoCrossfadeTiming.shouldPreloadNow(
                durationMs = 30_000L,
                positionMs = 22_500L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertFalse(
            IdleVideoCrossfadeTiming.shouldPreloadNow(
                durationMs = 30_000L,
                positionMs = 10_000L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
    }

    @Test
    fun `preload delay for short clips is zero immediately`() {
        assertEquals(
            0L,
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 2_000L,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertEquals(
            0L,
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 5_000L,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertTrue(
            IdleVideoCrossfadeTiming.shouldPreloadNow(
                durationMs = 2_000L,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
    }

    @Test
    fun `preload delay returns null for invalid duration`() {
        assertNull(
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = C.TIME_UNSET,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertNull(
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 0L,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertFalse(
            IdleVideoCrossfadeTiming.shouldPreloadNow(
                durationMs = C.TIME_UNSET,
                positionMs = 0L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
    }

    @Test
    fun `preload delay clamps when position exceeds duration`() {
        assertEquals(
            0L,
            IdleVideoCrossfadeTiming.preloadDelayMs(
                durationMs = 10_000L,
                positionMs = 12_000L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
        assertTrue(
            IdleVideoCrossfadeTiming.shouldPreloadNow(
                durationMs = 10_000L,
                positionMs = 12_000L,
                triggerBeforeEndMs = 1_500L,
                preloadLeadMs = 6_000L,
            ),
        )
    }
}
