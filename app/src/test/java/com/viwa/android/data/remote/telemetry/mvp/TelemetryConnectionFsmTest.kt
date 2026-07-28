package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConnectionFsmTest {
    @Test
    fun `should increment session generation monotonically`() {
        // given
        val fsm = TelemetryConnectionFsm()

        // when
        val first = fsm.nextSessionGeneration()
        val second = fsm.nextSessionGeneration()

        // then
        assertEquals(1L, first)
        assertEquals(2L, second)
    }

    @Test
    fun `should record structured transition on phase change`() {
        // given
        val fsm = TelemetryConnectionFsm(clockMs = { 1_000L })

        // when
        fsm.nextSessionGeneration()
        val transition = fsm.transition(TelemetryConnectionPhase.Connecting, "connect")

        // then
        assertNotNull(transition)
        assertEquals(TelemetryConnectionPhase.Idle, transition!!.from)
        assertEquals(TelemetryConnectionPhase.Connecting, transition.to)
        assertEquals(1L, transition.sessionGeneration)
        assertEquals("connect", transition.reason)
        assertEquals(1_000L, transition.atMs)
        assertTrue(fsm.formatTransitionLog(transition).contains("Idle → Connecting"))
    }

    @Test
    fun `should bump generation on supersede without duplicate transition when already superseded`() {
        // given
        val fsm = TelemetryConnectionFsm()
        fsm.nextSessionGeneration()

        // when
        val gen = fsm.bumpGenerationForSupersede("4001 duplicate session")

        // then
        assertEquals(2L, gen)
        assertEquals(TelemetryConnectionPhase.Superseded, fsm.phase)
    }

    @Test
    fun `should track backoff attempts`() {
        // given
        val fsm = TelemetryConnectionFsm()

        // when
        fsm.incrementBackoff()
        fsm.incrementBackoff()

        // then
        assertEquals(2, fsm.backoffAttempt)
        fsm.resetBackoff()
        assertEquals(0, fsm.backoffAttempt)
    }
}
