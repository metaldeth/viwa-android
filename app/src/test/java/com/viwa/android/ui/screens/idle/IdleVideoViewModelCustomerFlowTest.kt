package com.viwa.android.ui.screens.idle

import com.viwa.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleVideoViewModelCustomerFlowTest {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): IdleVideoViewModel {
        val configRepository = mockk<ConfigRepository>()
        coEvery { configRepository.get(any()) } returns null
        return IdleVideoViewModel(configRepository)
    }

    @Test
    fun `customer flow blocked cancels prewarm and resumes fresh countdown on unblock`() = runTest(scheduler) {
        // given
        val viewModel = createViewModel()
        try {
            viewModel.setActive(true)
            runCurrent()
            advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
            runCurrent()
            assertEquals(IdlePhase.Prewarm, viewModel.phase.value)

            // when — payment sheet opens
            viewModel.setCustomerFlowBlocked(true)
            runCurrent()

            // then
            assertEquals(IdlePhase.Hidden, viewModel.phase.value)

            // when — payment sheet closes
            viewModel.setCustomerFlowBlocked(false)
            runCurrent()
            advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
            runCurrent()

            // then — fresh countdown, not immediate visible
            assertEquals(IdlePhase.Prewarm, viewModel.phase.value)
        } finally {
            viewModel.setActive(false)
            runCurrent()
        }
    }

    @Test
    fun `customer flow blocked while inactive does not schedule idle on unblock`() = runTest(scheduler) {
        // given
        val viewModel = createViewModel()
        try {
            viewModel.setActive(false)
            viewModel.setCustomerFlowBlocked(true)
            runCurrent()

            // when
            viewModel.setCustomerFlowBlocked(false)
            runCurrent()
            advanceTimeBy(IdleVideoViewModel.IDLE_TIMEOUT_MS * 2)
            runCurrent()

            // then
            assertEquals(IdlePhase.Hidden, viewModel.phase.value)
        } finally {
            viewModel.setActive(false)
            runCurrent()
        }
    }
}
