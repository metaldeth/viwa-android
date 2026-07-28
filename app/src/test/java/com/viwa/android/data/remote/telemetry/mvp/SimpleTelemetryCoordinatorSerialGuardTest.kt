package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleTelemetryCoordinatorSerialGuardTest {
    private lateinit var server: MockWebServer
    private lateinit var configRepository: InMemoryConfigRepository
    private lateinit var secretStore: InMemoryMachineSecretStore
    private lateinit var jwtCache: MachineJwtCache
    private var wsConnectCalls = 0
    private lateinit var wsManager: MvpTelemetryWebSocketManager

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configRepository = InMemoryConfigRepository()
        secretStore = InMemoryMachineSecretStore()
        jwtCache = MachineJwtCache(SystemEpochMillisClock())
        wsConnectCalls = 0
        wsManager = mockk(relaxed = true)
        every { wsManager.connectionState } returns
            MutableStateFlow(com.viwa.android.data.remote.telemetry.ConnectionState.Disconnected())
        every { wsManager.connect(any(), any(), any(), any()) } answers { wsConnectCalls++ }
        every { wsManager.disconnect() } just runs
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `connectWithGuard refuses when ui serial differs from persisted`() = runTest {
        // given
        val coordinator = createCoordinator(this)
        seedEnrolled(coordinator, "VIWA-000004", "secret-old")
        // when
        val result = coordinator.connectWithGuard("VIWA-000005")
        // then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("не совпадает") == true)
        assertEquals(0, wsConnectCalls)
    }

    @Test
    fun `applyUiSerialChange clears secret resets enrollment and invalidates jwt cache`() = runTest {
        // given
        val coordinator = createCoordinator(this)
        seedEnrolled(coordinator, "VIWA-000004", "secret-old")
        jwtCache.getAccessToken("VIWA-000004", "secret-old") {
            Result.success(TokenResponseDto("tok", "Bearer", 3600))
        }
        assertTrue(secretStore.hasSecret("VIWA-000004"))
        // when
        val changed = coordinator.applyUiSerialChange("VIWA-000005")
        advanceUntilIdle()
        // then
        assertTrue(changed)
        assertFalse(secretStore.hasSecret("VIWA-000004"))
        val reg = coordinator.loadMachineRegistration()
        assertEquals("VIWA-000005", reg.serialNumber)
        assertFalse(reg.enrolled)
        assertFalse(MachineRegistration.isEnrolled(reg))
        assertTrue(reg.installationId.isNotBlank())
        val tokenAfter =
            jwtCache
                .getAccessToken("VIWA-000004", "secret-old") {
                    Result.success(TokenResponseDto("new-tok", "Bearer", 3600))
                }.getOrThrow()
        assertEquals("new-tok", tokenAfter)
    }

    @Test
    fun `disconnect persists pause and connectAuto does not open ws`() = runTest {
        // given
        val coordinator = createCoordinator(this)
        seedEnrolled(coordinator, "VIWA-000001", "sec")
        coordinator.connectAuto()
        advanceUntilIdle()
        wsConnectCalls = 0
        // when
        coordinator.disconnect()
        advanceUntilIdle()
        coordinator.connectAuto()
        advanceUntilIdle()
        // then
        assertEquals("true", configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER))
        assertEquals(0, wsConnectCalls)
    }

    @Test
    fun `register with new serial clears old secret`() = runTest {
        // given
        val coordinator = createCoordinator(this)
        val base = server.url("/").toString().removeSuffix("/")
        seedEnrolled(coordinator, "VIWA-000004", "old-secret")
        coordinator.saveTelemetryConfig(TelemetryConfig(apiUrl = base))
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(
                MachineRegistration.serializer(),
                coordinator.loadMachineRegistration().copy(regKey = "REG-0123456789AB"),
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"serialNumber":"VIWA-000005","machineSecret":"new-secret","machineId":"m5","id":"m5","wsUrl":"ws://127.0.0.1/ws","tokenEndpoint":"/api/v1/machines/token","installationId":"inst-new","protocolVersion":1,"heartbeatIntervalSeconds":30}""",
                ),
        )
        // when
        coordinator.registerMachine("REG-0123456789AB", "VIWA-000005").getOrThrow()
        // then
        assertFalse(secretStore.hasSecret("VIWA-000004"))
        assertTrue(secretStore.hasSecret("VIWA-000005"))
        assertEquals("VIWA-000005", coordinator.loadMachineRegistration().serialNumber)
    }

    private fun createCoordinator(appScope: CoroutineScope): SimpleTelemetryCoordinator =
        SimpleTelemetryCoordinator(
            apiClient =
                MvpTelemetryApiClient(
                    httpClient = OkHttpClient(),
                    json = json,
                    enrollmentKeyProvider = { "test-key" },
                ),
            wsManager = wsManager,
            cellsSyncCoordinator = mockk(relaxed = true),
            salesSyncCoordinator = mockk(relaxed = true),
            configRepository = configRepository,
            machineSecretStore = secretStore,
            jwtCache = jwtCache,
            flowTemperatureStore = FlowTemperatureStore(),
            networkObserver = mockk(relaxed = true),
            offlineEntitlementCoordinator = mockk(relaxed = true),
            technicianKeySessionCoordinator = mockk(relaxed = true),
            networkValidatedSideEffects = mockk(relaxed = true),
            appScope = appScope,
        )

    private suspend fun seedEnrolled(
        coordinator: SimpleTelemetryCoordinator,
        serial: String,
        secret: String,
    ) {
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            json.encodeToString(
                MachineRegistration.serializer(),
                MachineRegistration(
                    serialNumber = serial,
                    machineId = "m-1",
                    wsProtocolUrl = "ws://127.0.0.1/ws",
                    regKey = "REG-0123456789AB",
                    authScheme = MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                    isRegistered = true,
                    enrolled = true,
                    installationId = "inst-1",
                ),
            ),
        )
        secretStore.saveSecret(serial, secret)
        coordinator.saveTelemetryConfig(TelemetryConfig())
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
