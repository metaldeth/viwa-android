package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleTelemetryIdentityPersistenceTest {
    private val configRepository = mockk<ConfigRepository>(relaxed = true)

    @Test
    fun `ensureIdentity persists installationId and credential across calls`() = runTest {
        // given
        coEvery { configRepository.getJson(JsonStoreKeys.MACHINE_REGISTRATION) } returns null
        coEvery { configRepository.setJson(any(), any()) } returns Unit
        val coordinator =
            SimpleTelemetryCoordinator(
                apiClient = mockk(relaxed = true),
                wsManager = mockk(relaxed = true),
                cellsSyncCoordinator = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                configRepository = configRepository,
                machineSecretStore = InMemoryMachineSecretStore(),
                jwtCache = MachineJwtCache(SystemEpochMillisClock()),
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                logShipCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = this,
            )
        // when
        val first = coordinator.ensureIdentity(MachineRegistration())
        val second = coordinator.ensureIdentity(first)
        // then
        assertTrue(first.installationId.isNotBlank())
        assertTrue(first.machineCredential.startsWith("mch_"))
        assertEquals(first.installationId, second.installationId)
        assertEquals(first.machineCredential, second.machineCredential)
        coVerify(atLeast = 1) { configRepository.setJson(JsonStoreKeys.MACHINE_REGISTRATION, any()) }
    }
}
