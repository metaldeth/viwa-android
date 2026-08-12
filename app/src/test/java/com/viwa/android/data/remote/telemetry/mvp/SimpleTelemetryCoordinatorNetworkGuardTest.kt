package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.hardware.controller.FlowTemperatureStore
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleTelemetryCoordinatorNetworkGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `network validated skips scheduleConnect when ws lifecycle active`() = runTest {
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
        every { wsManager.shouldInitiateConnectOnNetworkValidated() } returns false

        val sideEffects = mockk<TelemetryNetworkValidatedSideEffectsCoordinator>(relaxed = true)
        val networkObserver = mockk<TelemetryNetworkObserver>(relaxed = true)
        val validatedCallbacks = mutableListOf<() -> Unit>()
        every { networkObserver.start() } just runs
        every { networkObserver.onValidatedAvailable = any() } answers {
            @Suppress("UNCHECKED_CAST")
            validatedCallbacks.add(invocation.args[0] as () -> Unit)
        }

        SimpleTelemetryCoordinator(
            apiClient = mockk(relaxed = true),
            wsManager = wsManager,
            cellsSyncCoordinator = mockk(relaxed = true),
            dispenseSyncCoordinator = mockk(relaxed = true),
            configRepository = configRepository,
            machineSecretStore = InMemoryMachineSecretStore(),
            jwtCache = MachineJwtCache(SystemEpochMillisClock()),
            flowTemperatureStore = FlowTemperatureStore(),
            networkObserver = networkObserver,
            offlineEntitlementCoordinator = mockk(relaxed = true),
            technicianKeySessionCoordinator = mockk(relaxed = true),
            logShipCoordinator = mockk(relaxed = true),
            networkValidatedSideEffects = sideEffects,
            appScope = this,
        )

        validatedCallbacks.single().invoke()
        advanceUntilIdle()

        verify(exactly = 1) { wsManager.notifyNetworkValidated() }
        verify(exactly = 1) { sideEffects.scheduleDebounced() }
        assertEquals(0, connectCalls)
    }

    private class InMemoryConfigRepository : com.viwa.android.data.repository.ConfigRepository {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun set(key: String, value: String) {
            store[key] = value
        }

        override suspend fun delete(key: String) {
            store.remove(key)
        }

        override suspend fun getJson(key: String): String? = store[key]

        override suspend fun setJson(key: String, jsonValue: String) {
            store[key] = jsonValue
        }
    }
}
