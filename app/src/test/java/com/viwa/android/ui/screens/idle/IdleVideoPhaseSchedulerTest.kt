package com.viwa.android.ui.screens.idle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleVideoPhaseSchedulerTest {
    private val scheduler = TestCoroutineScheduler()

    private fun createScheduler(
        scope: CoroutineScope,
        screenActive: () -> Boolean = { true },
        enabledVideoIds: () -> List<String> = { listOf("video1") },
    ): Pair<IdleVideoPhaseScheduler, MutableList<IdlePhase>> {
        val phases = mutableListOf<IdlePhase>()
        val schedulerInstance =
            IdleVideoPhaseScheduler(
                scope = scope,
                isScreenActive = screenActive,
                enabledVideoIds = enabledVideoIds,
                onPhaseChanged = { phase ->
                    phases.add(phase)
                },
            )
        return schedulerInstance to phases
    }

    @Test
    fun `transitions Hidden to Prewarm to Visible when prewarm ready`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this)

        // when
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()

        // then
        assertEquals(IdlePhase.Prewarm, phases.last())

        // when
        idleScheduler.onPrewarmReady()
        runCurrent()

        // then
        assertEquals(IdlePhase.Visible, phases.last())
        idleScheduler.clear()
    }

    @Test
    fun `resetTimer returns to Hidden and reschedules prewarm`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this)
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()
        idleScheduler.onPrewarmReady()
        runCurrent()
        assertEquals(IdlePhase.Visible, phases.last())

        // when
        idleScheduler.cancelAndHide()
        idleScheduler.scheduleIdle()
        runCurrent()

        // then
        assertEquals(IdlePhase.Hidden, phases.last())

        // when
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()

        // then
        assertEquals(IdlePhase.Prewarm, phases.last())
        idleScheduler.clear()
    }

    @Test
    fun `setActive false cancels timer and hides overlay`() = runTest(scheduler) {
        // given
        var screenActive = true
        val (idleScheduler, phases) = createScheduler(this, screenActive = { screenActive })
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()
        assertEquals(IdlePhase.Prewarm, phases.last())

        // when
        screenActive = false
        idleScheduler.cancelAndHide()
        runCurrent()

        // then
        assertEquals(IdlePhase.Hidden, phases.last())
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS)
        runCurrent()
        assertEquals(IdlePhase.Hidden, phases.last())
        idleScheduler.clear()
    }

    @Test
    fun `empty enabled videos never leaves Hidden`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this, enabledVideoIds = { emptyList() })

        // when
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS * 2)
        runCurrent()

        // then
        assertEquals(listOf(IdlePhase.Hidden), phases)
    }

    @Test
    fun `prewarm ready timeout hides overlay and reschedules`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this)
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()
        assertEquals(IdlePhase.Prewarm, phases.last())

        // when — no onPrewarmReady within timeout
        advanceTimeBy(IdleVideoViewModel.PREWARM_READY_TIMEOUT_MS)
        runCurrent()

        // then
        assertEquals(IdlePhase.Hidden, phases.last())

        // when — timer restarts inside the same job loop
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()

        // then
        assertEquals(IdlePhase.Prewarm, phases.last())
        idleScheduler.clear()
    }

    @Test
    fun `race onPrewarmReady and cancelAndHide ends Hidden without Visible`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this)
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()
        assertEquals(IdlePhase.Prewarm, phases.last())

        // when — touch at the exact moment prewarm completes
        idleScheduler.onPrewarmReady()
        idleScheduler.cancelAndHide()
        runCurrent()

        // then
        assertFalse(phases.contains(IdlePhase.Visible))
        assertEquals(IdlePhase.Hidden, phases.last())
        idleScheduler.clear()
    }

    @Test
    fun `late onPrewarmReady after Hidden has no effect`() = runTest(scheduler) {
        // given
        val (idleScheduler, phases) = createScheduler(this)
        idleScheduler.scheduleIdle()
        runCurrent()
        advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
        runCurrent()
        assertEquals(IdlePhase.Prewarm, phases.last())

        // when — dismiss while still prewarming
        idleScheduler.cancelAndHide()
        runCurrent()
        assertEquals(IdlePhase.Hidden, phases.last())
        val phaseCountAfterHide = phases.size

        idleScheduler.onPrewarmReady()
        runCurrent()

        // then
        assertEquals(phaseCountAfterHide, phases.size)
        assertFalse(phases.contains(IdlePhase.Visible))
        assertEquals(IdlePhase.Hidden, phases.last())
        idleScheduler.clear()
    }
}
