package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleTelemetryCoordinatorLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val secretStore = InMemoryMachineSecretStore()

    @Test
    fun `parallel connect calls invoke ws connect once`() = runTest {
        val configRepository = InMemoryConfigRepository()
        val enrolled =
            MachineRegistration(
                serialNumber = "VIWA-000001",
                machineCredential = "mch_test",
                machineKey = "mch_test",
                wsProtocolUrl = "ws://127.0.0.1/ws",
                isRegistered = true,
                enrolled = true,
                installationId = "inst-1",
            )
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(MachineRegistration.serializer(), enrolled),
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
        val coordinator =
            SimpleTelemetryCoordinator(
                apiClient = mockk(relaxed = true),
                wsManager = wsManager,
                cellsSyncCoordinator = mockk(relaxed = true),
                salesSyncCoordinator = mockk(relaxed = true),
                configRepository = configRepository,
                machineSecretStore = secretStore,
                jwtCache = MachineJwtCache(SystemEpochMillisClock()),
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = this,
            )
        coordinator.connect()
        coordinator.connect()
        coordinator.reconnect()
        advanceUntilIdle()
        assertTrue("expected single ws connect, got $connectCalls", connectCalls == 1)
    }

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
