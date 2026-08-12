package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.logging.AppLogFileStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

class LogShipCoordinatorTest {
    private lateinit var apiClient: MvpTelemetryApiClient
    private lateinit var appLogFileStore: AppLogFileStore
    private lateinit var bearerProvider: MachineOutboxBearerTokenProvider
    private lateinit var configRepository: ConfigRepository
    private lateinit var wsManager: MvpTelemetryWebSocketManager
    private lateinit var coordinator: LogShipCoordinator

  private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        apiClient = mockk(relaxed = true)
        appLogFileStore = mockk(relaxed = true)
        bearerProvider = mockk(relaxed = true)
        configRepository = mockk(relaxed = true)
        wsManager = mockk(relaxed = true)

        every { wsManager.isNetworkValidated() } returns true
        every { wsManager.logShipCapability() } returns null
        coEvery { bearerProvider.resolveBearerToken() } returns "jwt-token"
        every { appLogFileStore.hasPendingContent() } returns true
        coEvery {
            appLogFileStore.prepareShipSnapshot()
        } returns
            AppLogFileStore.ShipSnapshot(
                gzipBytes = byteArrayOf(1, 2, 3),
                shippedByteCount = 42,
                periodStart = "2026-08-12T10:00:00Z",
                periodEnd = "2026-08-12T10:00:01Z",
            )
        coEvery { apiClient.uploadMachineLogs(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val registration =
            MachineRegistration(
                serialNumber = "SN-001",
                isRegistered = true,
                enrolled = true,
            )
        coEvery {
            configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.MACHINE_REGISTRATION)
        } returns json.encodeToString(registration)
        coEvery {
            configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.TELEMETRY_CONFIG)
        } returns null

        coordinator =
            LogShipCoordinator(
                apiClient = apiClient,
                appLogFileStore = appLogFileStore,
                bearerTokenProvider = bearerProvider,
                configRepository = configRepository,
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
    }

    @Test
    fun `shipLogs uploads gzip snapshot when registered`() = runTest {
        // when
        coordinator.shipLogs()

        // then
        coVerify {
            apiClient.uploadMachineLogs(
                endpoint = "https://tl.vitamin-water.ru/api/v1/machines/logs/upload",
                bearerToken = "jwt-token",
                gzipBytes = byteArrayOf(1, 2, 3),
                periodStart = "2026-08-12T10:00:00Z",
                periodEnd = "2026-08-12T10:00:01Z",
                appVersionName = com.viwa.android.BuildConfig.VERSION_NAME,
            )
        }
        coVerify { appLogFileStore.commitShip(42) }
    }

    @Test
    fun `shipLogs soft-fails on server 404`() = runTest {
        // given
        coEvery { apiClient.uploadMachineLogs(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(LogShipFeatureDisabledException())

        // when
        coordinator.shipLogs()
        coordinator.shipLogs()

        // then — second attempt skipped after feature disabled
        coVerify(exactly = 0) { appLogFileStore.commitShip(any()) }
    }
}
