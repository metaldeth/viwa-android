package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.hardware.controller.FlowTemperatureStore
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleTelemetryCoordinatorReconnectGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `reconcile restores enrolled flags when secret exists`() = runTest {
        // given
        val configRepository = InMemoryConfigRepository()
        val secretStore = InMemoryMachineSecretStore()
        secretStore.saveSecret("VIWA-000010", "sec-10")
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(
                MachineRegistration.serializer(),
                MachineRegistration(
                    serialNumber = "VIWA-000010",
                    enrolled = false,
                    isRegistered = false,
                ),
            ),
        )
        val coordinator = createCoordinator(this, configRepository, secretStore)

        // when
        val reconciled = coordinator.reconcilePersistedRegistration()

        // then
        assertTrue(MachineRegistration.isEnrolled(reconciled))
        assertEquals(MachineRegistration.AUTH_SCHEME_STABLE_SECRET, reconciled.authScheme)
        assertTrue(coordinator.canReconnectWithPersistedCredentials())
    }

    @Test
    fun `connect skips ws when enrolled but secret missing`() = runTest {
        // given
        val configRepository = InMemoryConfigRepository()
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(
                MachineRegistration.serializer(),
                MachineRegistration(
                    serialNumber = "VIWA-000011",
                    authScheme = MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                    enrolled = true,
                    isRegistered = true,
                    wsProtocolUrl = "ws://127.0.0.1/ws",
                    installationId = "inst-11",
                ),
            ),
        )
        configRepository.setJson(
            JsonStoreKeys.TELEMETRY_CONFIG,
            json.encodeToString(TelemetryConfig.serializer(), TelemetryConfig()),
        )
        var connectCalls = 0
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        every { wsManager.connectionState } returns
            MutableStateFlow(com.viwa.android.data.remote.telemetry.ConnectionState.Disconnected())
        every { wsManager.connect(any(), any(), any(), any()) } answers { connectCalls++ }
        every { wsManager.disconnect() } just runs
        val coordinator = createCoordinator(this, configRepository, InMemoryMachineSecretStore(), wsManager)

        // when
        coordinator.connectAuto()
        advanceUntilIdle()

        // then
        assertFalse(coordinator.canReconnectWithPersistedCredentials())
        assertEquals(0, connectCalls)
    }

    private fun createCoordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        configRepository: ConfigRepository,
        secretStore: InMemoryMachineSecretStore,
        wsManager: MvpTelemetryWebSocketManager = mockk(relaxed = true),
    ): SimpleTelemetryCoordinator =
        SimpleTelemetryCoordinator(
            apiClient = mockk(relaxed = true),
            wsManager = wsManager,
            cellsSyncCoordinator = mockk(relaxed = true),
            dispenseSyncCoordinator = mockk(relaxed = true),
            configRepository = configRepository,
            machineSecretStore = secretStore,
            jwtCache = MachineJwtCache(SystemEpochMillisClock()),
            flowTemperatureStore = FlowTemperatureStore(),
            networkObserver = mockk(relaxed = true),
            offlineEntitlementCoordinator = mockk(relaxed = true),
            technicianKeySessionCoordinator = mockk(relaxed = true),
            logShipCoordinator = mockk(relaxed = true),
            networkValidatedSideEffects = mockk(relaxed = true),
            appScope = scope,
        )

    private class InMemoryConfigRepository : ConfigRepository {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun set(key: String, value: String) {
            store[key] = value
        }

        override suspend fun delete(key: String) {
            store.remove(key)
        }

        override suspend fun getJson(key: String): String? = store[key]

        override suspend fun setJson(key: String, json: String) {
            store[key] = json
        }
    }
}
