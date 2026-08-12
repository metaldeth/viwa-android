package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineEntitlementSessionCoordinator
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeySessionCoordinator
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryNetworkValidatedSideEffectsCoordinatorTest {
    private lateinit var outboxDrain: MachineOutboxDrainCoordinator
    private lateinit var offlineEntitlement: OfflineEntitlementSessionCoordinator
    private lateinit var technicianKeys: TechnicianKeySessionCoordinator
    private lateinit var coordinator: TelemetryNetworkValidatedSideEffectsCoordinator

    @Before
    fun setUp() {
        outboxDrain = mockk(relaxed = true)
        offlineEntitlement = mockk(relaxed = true)
        technicianKeys = mockk(relaxed = true)
    }

    @Test
    fun `burst scheduleDebounced runs side effects once`() = runTest {
        coordinator =
            TelemetryNetworkValidatedSideEffectsCoordinator(
                outboxDrainCoordinator = outboxDrain,
                offlineEntitlementCoordinator = offlineEntitlement,
                technicianKeySessionCoordinator = technicianKeys,
                logShipCoordinator = mockk(relaxed = true),
                appScope = this,
            )

        repeat(5) { coordinator.scheduleDebounced() }
        advanceTimeBy(TelemetryNetworkValidatedSideEffectsCoordinator.DEBOUNCE_MS - 1)
        coVerify(exactly = 0) { outboxDrain.onNetworkValidated() }
        advanceTimeBy(1)
        advanceUntilIdle()

        coVerify(exactly = 1) { outboxDrain.onNetworkValidated() }
        verify(exactly = 1) { offlineEntitlement.onNetworkValidated() }
        verify(exactly = 1) { technicianKeys.onNetworkValidated() }
        assertFalse(coordinator.debounceJobActiveForTests())
    }
}
