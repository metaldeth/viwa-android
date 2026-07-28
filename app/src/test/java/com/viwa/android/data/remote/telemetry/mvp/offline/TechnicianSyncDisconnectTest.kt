package com.viwa.android.data.remote.telemetry.mvp.offline

import com.viwa.android.data.local.technician.FakeTechnicianAllowlistStateDao
import com.viwa.android.data.local.technician.TechnicianAllowlistStore
import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.mockOtaCoordinatorProvider
import com.viwa.android.domain.offline.BoundedTelemetryClock
import com.viwa.android.domain.offline.OfflineSigningKeysStore
import com.viwa.android.domain.technician.TechnicianAllowlistVerifier
import com.viwa.android.domain.technician.TechnicianSessionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicianAllowlistSyncCoordinatorDisconnectTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun `onDisconnect cancels periodic allowlist sync job`() =
        runBlocking {
            val (coordinator, stateDao) = createCoordinator(apiDelayMs = 0L)
            coordinator.onHello(
                capability = sampleCapability(),
                serverTechnicianKeysEnabled = true,
                serverTimeUtc = "2026-07-27T10:00:00.000Z",
                signingPublicKeys = null,
                revocationEpoch = 0,
            )
            delay(100)
            coordinator.onDisconnect()
            delay(100)

            assertNull(periodicJobForTests(coordinator))
            assertTrue(stateDao.getState()?.hasTrustedAllowlistSync == true)
        }

    @Test
    fun `onDisconnect while hello sync is in flight does not schedule periodic sync`() =
        runBlocking {
            val (coordinator, _) = createCoordinator(apiDelayMs = 1_000L)
            coordinator.onHello(
                capability = sampleCapability(),
                serverTechnicianKeysEnabled = true,
                serverTimeUtc = "2026-07-27T10:00:00.000Z",
                signingPublicKeys = null,
                revocationEpoch = 0,
            )
            delay(50)
            coordinator.onDisconnect()
            delay(1_100)

            assertNull(periodicJobForTests(coordinator))
        }

    private suspend fun createCoordinator(
        apiDelayMs: Long,
    ): Pair<TechnicianAllowlistDeltaSyncCoordinator, FakeTechnicianAllowlistStateDao> {
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val apiClient = mockk<MvpTelemetryApiClient>()
        val bearerTokenProvider = mockk<MachineOutboxBearerTokenProvider>()
        val stateDao = FakeTechnicianAllowlistStateDao()
        val allowlistStore = mockk<TechnicianAllowlistStore>()
        val policyStore = TechnicianKeyPolicyStore(stateDao)
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
        coEvery { allowlistStore.getCursor() } returns "0"
        coEvery {
            allowlistStore.applyDeltaTransactionally(any(), any(), any(), any())
        } returns Unit
        coEvery { bearerTokenProvider.resolveBearerToken() } returns "test-token"
        if (apiDelayMs > 0L) {
            coEvery {
                apiClient.fetchTechnicianAllowlistDelta(any(), any(), any())
            } coAnswers {
                delay(apiDelayMs)
                sampleDeltaResponse()
            }
        } else {
            coEvery {
                apiClient.fetchTechnicianAllowlistDelta(any(), any(), any())
            } returns sampleDeltaResponse()
        }
        every { wsManager.isNetworkValidated() } returns true
        val coordinator =
            TechnicianAllowlistDeltaSyncCoordinator(
                apiClient = apiClient,
                allowlistStore = allowlistStore,
                policyStore = policyStore,
                allowlistVerifier = TechnicianAllowlistVerifier(OfflineSigningKeysStore()),
                signingKeysStore = OfflineSigningKeysStore(),
                sessionStore = TechnicianSessionStore(),
                clock = clock,
                bearerTokenProvider = bearerTokenProvider,
                metrics = mockk(relaxed = true),
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                appScope = appScope,
            )
        return coordinator to stateDao
    }

    private fun sampleCapability(): MvpTechnicianKeysCapabilityDto =
        MvpTechnicianKeysCapabilityDto(
            validateEndpoint = "/validate",
            allowlistDeltaEndpoint = "/delta",
            auditBatchEndpoint = "/audit",
            syncIntervalSeconds = 60,
        )

    private fun sampleDeltaResponse(): Result<TechnicianAllowlistDeltaResponseDto> =
        Result.success(
            TechnicianAllowlistDeltaResponseDto(
                records = emptyList(),
                tombstones = emptyList(),
                nextCursor = "0",
                revocationEpoch = 0,
                serverTimeUtc = "2026-07-27T10:00:00.000Z",
            ),
        )

    private fun periodicJobForTests(coordinator: TechnicianAllowlistDeltaSyncCoordinator): Job? {
        val field = TechnicianAllowlistDeltaSyncCoordinator::class.java.getDeclaredField("periodicJob")
        field.isAccessible = true
        return field.get(coordinator) as Job?
    }
}

class MvpTelemetryWebSocketManagerDisconnectLifecycleTest {
    @Test
    fun `disconnect clears capabilities and stops periodic jobs`() {
        val outboxDrain = mockk<MachineOutboxDrainCoordinator>(relaxed = true)
        val technicianCoordinator = mockk<TechnicianKeySessionCoordinator>(relaxed = true)
        val manager =
            MvpTelemetryWebSocketManager(
                appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                networkTrafficLogger = mockk(relaxed = true),
                ackRouter = mockk(relaxed = true),
                outboxDrainCoordinator = outboxDrain,
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = technicianCoordinator,
                appUpdateCoordinatorProvider = mockOtaCoordinatorProvider(),
            )

        manager.disconnect()

        assertNull(manager.outboxBatchCapability())
        assertNull(manager.technicianKeysCapability())
        verify(exactly = 1) { outboxDrain.stopPeriodicFlush() }
        verify(exactly = 1) { technicianCoordinator.onDisconnect() }
    }
}
