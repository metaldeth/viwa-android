package com.viwa.android.data.remote.telemetry.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TelemetryReconnectBackoffTest {
    @Test
    fun `should use flat backoff after supersede`() {
        // when
        val delay = TelemetryReconnectBackoff.delayMs(attempt = 0, supersededFlatBackoff = true)

        // then
        assertEquals(TelemetryReconnectBackoff.SUPERSEDE_BACKOFF_MS, delay)
    }

    @Test
    fun `should apply full jitter within cap`() {
        // given
        val random = Random(42)

        // when
        repeat(50) {
            val delay = TelemetryReconnectBackoff.delayMs(attempt = 2, random = random)
            assertTrue(delay in 0..5_000L)
        }
    }

    @Test
    fun `should cap attempt index at last delay slot`() {
        // given
        val random = Random(1)

        // when
        val delay = TelemetryReconnectBackoff.delayMs(attempt = 99, random = random)

        // then
        assertTrue(delay in 0..30_000L)
    }
}
