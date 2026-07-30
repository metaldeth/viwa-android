package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.security.InMemoryMachineSecretStore
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.hardware.controller.FlowTemperatureStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory ConfigRepository for coordinator tests. */
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

class SimpleTelemetryCoordinatorTest {
    private lateinit var server: MockWebServer
    private lateinit var configRepository: InMemoryConfigRepository
    private lateinit var machineSecretStore: InMemoryMachineSecretStore
    private lateinit var jwtCache: MachineJwtCache
    private lateinit var coordinator: SimpleTelemetryCoordinator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configRepository = InMemoryConfigRepository()
        machineSecretStore = InMemoryMachineSecretStore()
        jwtCache = MachineJwtCache(SystemEpochMillisClock())
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        val apiClient =
            MvpTelemetryApiClient(
                httpClient = OkHttpClient(),
                json = json,
                enrollmentKeyProvider = { "test-key" },
            )
        coordinator =
            SimpleTelemetryCoordinator(
                apiClient = apiClient,
                wsManager =
                    MvpTelemetryWebSocketManager(
                        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                        networkTrafficLogger = mockk(relaxed = true),
                        ackRouter = mockk(relaxed = true),
                        outboxDrainCoordinator = mockk(relaxed = true),
                        offlineEntitlementCoordinator = mockk(relaxed = true),
                        technicianKeySessionCoordinator = mockk(relaxed = true),
                        appUpdateCoordinatorProvider = mockOtaCoordinatorProvider(),
                    ),
                cellsSyncCoordinator = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                configRepository = configRepository,
                machineSecretStore = machineSecretStore,
                jwtCache = jwtCache,
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reserve and enroll persists serial and credential`() = runTest {
        // given
        val base = server.url("/").toString().removeSuffix("/")
        coordinator.saveTelemetryConfig(TelemetryConfig(apiUrl = base))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"serialNumber":"VIWA-000010","reservationToken":"rt","expiresAt":"2026-07-17T00:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"machineId":"m-10","serialNumber":"VIWA-000010","wsProtocolUrl":"ws://localhost/ws"}"""),
        )
        // when
        val serial = coordinator.reserveFreeSerial().getOrThrow()
        coordinator.enrollMachine(serial, rebind = false).getOrThrow()
        val reg = coordinator.loadMachineRegistration()
        // then
        assertEquals("VIWA-000010", reg.serialNumber)
        assertTrue(reg.machineCredential.startsWith("mch_"))
        assertTrue(reg.enrolled)
    }

    @Test
    fun `enroll conflict surfaces SerialAlreadyBoundException`() = runTest {
        // given
        val base = server.url("/").toString().removeSuffix("/")
        coordinator.saveTelemetryConfig(TelemetryConfig(apiUrl = base))
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"code":"SERIAL_ALREADY_BOUND","message":"bound"}"""),
        )
        // when
        val result = coordinator.enrollMachine("VIWA-000001", rebind = false)
        // then
        assertTrue(result.exceptionOrNull() is SerialAlreadyBoundException)
    }

    @Test
    fun `loadTelemetryConfig migrates legacy ws away for mvp mode`() = runTest {
        // given
        val jsonEncoder =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        configRepository.setJson(
            JsonStoreKeys.TELEMETRY_CONFIG,
            jsonEncoder.encodeToString(
                TelemetryConfig.serializer(),
                TelemetryConfig(
                    apiUrl = TelemetryConfig.DEFAULT_API_URL,
                    wsUrl = "ws://185.46.8.39:8315/ws",
                ),
            ),
        )
        // when
        val loaded = coordinator.loadTelemetryConfig()
        // then
        assertEquals("", loaded.wsUrl)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect with legacy stored ws resolves derived wss url`() = runTest {
        // given
        val jsonEncoder =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        configRepository.setJson(
            JsonStoreKeys.TELEMETRY_CONFIG,
            jsonEncoder.encodeToString(
                TelemetryConfig.serializer(),
                TelemetryConfig(
                    apiUrl = TelemetryConfig.DEFAULT_API_URL,
                    wsUrl = "ws://185.46.8.39:8315/ws",
                ),
            ),
        )
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            jsonEncoder.encodeToString(
                com.viwa.android.domain.model.MachineRegistration.serializer(),
                com.viwa.android.domain.model.MachineRegistration(
                    serialNumber = "VIWA-000001",
                    machineCredential = "mch_test",
                    machineKey = "mch_test",
                    wsProtocolUrl = "ws://185.46.8.39:8315/ws",
                    isRegistered = true,
                    enrolled = true,
                    installationId = "inst-1",
                ),
            ),
        )
        var connectedUrl: String? = null
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        every { wsManager.connectionState } returns
            MutableStateFlow(com.viwa.android.data.remote.telemetry.ConnectionState.Disconnected())
        every { wsManager.connect(any(), any(), any(), any()) } answers {
            connectedUrl = firstArg()
        }
        every { wsManager.disconnect() } just runs
        val trackingCoordinator =
            SimpleTelemetryCoordinator(
                apiClient = mockk(relaxed = true),
                wsManager = wsManager,
                cellsSyncCoordinator = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                configRepository = configRepository,
                machineSecretStore = machineSecretStore,
                jwtCache = jwtCache,
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = this,
            )
        // when
        trackingCoordinator.connect()
        advanceUntilIdle()
        // then
        assertEquals(
            "wss://tl.vitamin-water.ru/api/v1/machines/ws",
            connectedUrl,
        )
    }

    @Test
    fun `reserveFreeSerial fails locally without enrollment key`() = runTest {
        // given
        val clientWithoutKey =
            MvpTelemetryApiClient(
                httpClient = OkHttpClient(),
                json =
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    },
                enrollmentKeyProvider = { "" },
            )
        val localCoordinator =
            SimpleTelemetryCoordinator(
                apiClient = clientWithoutKey,
                wsManager =
                    MvpTelemetryWebSocketManager(
                        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                        networkTrafficLogger = mockk(relaxed = true),
                        ackRouter = mockk(relaxed = true),
                        outboxDrainCoordinator = mockk(relaxed = true),
                        offlineEntitlementCoordinator = mockk(relaxed = true),
                        technicianKeySessionCoordinator = mockk(relaxed = true),
                        appUpdateCoordinatorProvider = mockOtaCoordinatorProvider(),
                    ),
                cellsSyncCoordinator = mockk(relaxed = true),
                dispenseSyncCoordinator = mockk(relaxed = true),
                configRepository = configRepository,
                machineSecretStore = machineSecretStore,
                jwtCache = jwtCache,
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = this,
            )
        localCoordinator.saveTelemetryConfig(TelemetryConfig())
        // when
        val result = localCoordinator.reserveFreeSerial()
        // then
        assertTrue(result.exceptionOrNull() is MissingEnrollmentKeyException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provisionMachine persists serial secret and registration key`() = runTest {
        // given
        val base = server.url("/").toString().removeSuffix("/")
        coordinator.saveTelemetryConfig(TelemetryConfig(apiUrl = base))
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "id":"id-prov",
                      "machineId":"m-prov",
                      "serialNumber":"VIWA-000099",
                      "installationId":"inst-1",
                      "machineSecret":"sec_prov",
                      "tokenEndpoint":"/api/v1/machines/token",
                      "wsUrl":"ws://localhost/ws",
                      "protocolVersion":1,
                      "heartbeatIntervalSeconds":30,
                      "registrationKey":"REG-0123456789AB"
                    }
                    """.trimIndent(),
                ),
        )
        // when
        coordinator.provisionMachine().getOrThrow()
        val reg = coordinator.loadMachineRegistration()
        // then
        assertEquals("VIWA-000099", reg.serialNumber)
        assertEquals("REG-0123456789AB", reg.regKey)
        assertTrue(reg.enrolled)
        assertTrue(machineSecretStore.hasSecret("VIWA-000099"))
    }

    @Test
    fun `provisionMachine when already enrolled is no-op success`() = runTest {
        // given
        val jsonEncoder =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
        configRepository.setJson(
            JsonStoreKeys.MACHINE_REGISTRATION,
            jsonEncoder.encodeToString(
                com.viwa.android.domain.model.MachineRegistration.serializer(),
                com.viwa.android.domain.model.MachineRegistration(
                    serialNumber = "VIWA-000001",
                    machineId = "m1",
                    isRegistered = true,
                    enrolled = true,
                    installationId = "inst-1",
                    authScheme = com.viwa.android.domain.model.MachineRegistration.AUTH_SCHEME_STABLE_SECRET,
                ),
            ),
        )
        machineSecretStore.saveSecret("VIWA-000001", "existing-secret")
        // when
        val result = coordinator.provisionMachine()
        // then
        assertTrue(result.isSuccess)
        assertEquals(0, server.requestCount)
    }
}
