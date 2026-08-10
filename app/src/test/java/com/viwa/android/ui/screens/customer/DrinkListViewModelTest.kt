package com.viwa.android.ui.screens.customer

import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = executor.asCoroutineDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking {
            DrinkListViewModelTestSupport.clearTrackedViewModels(mainDispatcher)
        }
        Dispatchers.resetMain()
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 5000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            withContext(mainDispatcher) {}
            yield()
        }
        return false
    }

    private suspend fun flushMain(times: Int = 12) {
        repeat(times) {
            withContext(mainDispatcher) {}
            yield()
        }
    }

    private fun createTestTelemetry(): ViwaTelemetryService {
        val mock = mockk<ViwaTelemetryService>(relaxed = true)
        every { mock.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected()).asStateFlow()
        every { mock.subscribeInfo } returns MutableStateFlow(null).asStateFlow()
        every { mock.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.offlineLoyaltyDenyReason } returns
            MutableSharedFlow<OfflineAuthorizationReason>(extraBufferCapacity = 16).asSharedFlow()
        return mock
    }

    @Test
    fun entitlementScan_resetsSubscriptionExitTimerOnlyForValidScan() =
        runBlocking {
            val validScans = MutableSharedFlow<String>(extraBufferCapacity = 1)
            val invalidScans = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val telemetry = createTestTelemetry()
            every { telemetry.loyaltyCardClientScans } returns validScans.asSharedFlow()
            every { telemetry.invalidLoyaltyCardScans } returns invalidScans.asSharedFlow()
            val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
            flushMain(24)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionExitRemainingSeconds = 3,
                ),
            )

            invalidScans.emit(Unit)
            flushMain()
            assertEquals(3, vm.state.value.subscriptionExitRemainingSeconds)

            validScans.emit("660e8400-e29b-41d4-a716-446655440010")
            assertTrue(
                awaitCondition {
                    vm.state.value.subscriptionExitRemainingSeconds == 10
                },
            )
        }

    @Test
    fun waterPourHold_pausesSubscriptionExitAndRestartsItOnRelease() =
        runBlocking {
            val vm = DrinkListViewModelTestSupport.createViewModel()
            flushMain(24)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                ),
            )
            vm.resetSubscriptionExitTimer()
            assertTrue(vm.isSubscriptionExitTimerRunningForUnitTests())

            vm.waterPourPointerDown()
            assertFalse(vm.isSubscriptionExitTimerRunningForUnitTests())
            assertEquals(10, vm.state.value.subscriptionExitRemainingSeconds)

            vm.waterPourPointerUp()
            assertTrue(vm.isSubscriptionExitTimerRunningForUnitTests())
            assertEquals(10, vm.state.value.subscriptionExitRemainingSeconds)
        }

    @Test
    fun activeEntitlement_allowsPrimaryPourWithoutPaymentSheet() =
        runBlocking {
            val vm = DrinkListViewModelTestSupport.createViewModel()
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    isSubscriptionActive = true,
                    subscriptionVolumeMl = 500,
                    activeContainer = DrinkListViewModelTestSupport.sampleContainer(),
                    selectedVolumeMl = 300,
                ),
            )
            vm.primaryAction { _, _, _, _, _, _ -> }
            flushMain(24)
            assertFalse(vm.state.value.paymentSheetVisible)
        }
}
