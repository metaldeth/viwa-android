package com.viwa.android.services.telemetry

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.remote.telemetry.mvp.SimpleTelemetryCoordinator
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViwaTelemetryServiceColdStartReconnectTest {
    @Test
    fun `cold start connects when coordinator reports persisted credentials`() = runTest {
        // given
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        coEvery { configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) } returns "false"
        val coordinator = mockk<SimpleTelemetryCoordinator>(relaxed = true)
        coEvery { coordinator.canReconnectWithPersistedCredentials() } returns true
        val service =
            ViwaTelemetryService(
                configRepository = configRepository,
                mvpCoordinator = coordinator,
                wsManager = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                offlinePourAuthorizationService = mockk(relaxed = true),
                scope = this,
            )
        advanceUntilIdle()

        // when
        advanceTimeBy(3_100)
        advanceUntilIdle()

        // then
        coVerify(exactly = 1) { coordinator.canReconnectWithPersistedCredentials() }
        coVerify(exactly = 1) { coordinator.connect() }
    }

    @Test
    fun `cold start skips connect when credentials missing`() = runTest {
        // given
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        coEvery { configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) } returns "false"
        val coordinator = mockk<SimpleTelemetryCoordinator>(relaxed = true)
        coEvery { coordinator.canReconnectWithPersistedCredentials() } returns false
        coEvery { coordinator.loadMachineRegistration() } returns
            MachineRegistration(
                serialNumber = "VIWA-000099",
                enrolled = true,
                isRegistered = true,
            )
        val service =
            ViwaTelemetryService(
                configRepository = configRepository,
                mvpCoordinator = coordinator,
                wsManager = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                offlinePourAuthorizationService = mockk(relaxed = true),
                scope = this,
            )
        advanceUntilIdle()

        // when
        advanceTimeBy(3_100)
        advanceUntilIdle()

        // then
        coVerify(exactly = 0) { coordinator.connect() }
    }
}
