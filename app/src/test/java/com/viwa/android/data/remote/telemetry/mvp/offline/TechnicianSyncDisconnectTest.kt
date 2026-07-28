package com.viwa.android.data.remote.telemetry.mvp.offline

import androidx.room.withTransaction
import com.viwa.android.data.local.technician.FakeTechnicianAllowlistDao
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
import com.viwa.android.domain.technician.TechnicianKeyMetrics
import com.viwa.android.domain.technician.TechnicianSessionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicianAllowlistSyncCoordinatorDisconnectTest {
    @Test
    fun `onDisconnect cancels periodic allowlist sync job`() = runTest {
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val apiClient = mockk<MvpTelemetryApiClient>(relaxed = true)
        val bearerTokenProvider = mockk<MachineOutboxBearerTokenProvider>(relaxed = true)
        val allowlistDao = FakeTechnicianAllowlistDao()
        val stateDao = FakeTechnicianAllowlistStateDao()
        val db = mockk<com.viwa.android.data.local.db.ViwaDatabase>()
        coEvery { db.withTransaction(any<suspend () -> Unit>()) } coAnswers {
            firstArg<suspend () -> Unit>()()
        }
        val allowlistStore = TechnicianAllowlistStore(db, allowlistDao, stateDao)
        val policyStore = TechnicianKeyPolicyStore(stateDao)
        val clock = BoundedTelemetryClock().apply { updateFromServer("2026-07-27T10:00:00.000Z") }
        coEvery { bearerTokenProvider.resolveBearerToken() } returns "test-token"
        coEvery {
            apiClient.fetchTechnicianAllowlistDelta(any(), any(), any())
        } returns
            Result.success(
                TechnicianAllowlistDeltaResponseDto(
                    records = emptyList(),
                    tombstones = emptyList(),
                    nextCursor = "0",
                    revocationEpoch = 0,
                    serverTimeUtc = "2026-07-27T10:00:00.000Z",
                ),
            )
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
                metrics = mockk<TechnicianKeyMetrics>(relaxed = true),
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                appScope = CoroutineScope(Dispatchers.Unconfined),
            )

        coordinator.onHello(
            capability =
                MvpTechnicianKeysCapabilityDto(
                    validateEndpoint = "/validate",
                    allowlistDeltaEndpoint = "/delta",
                    auditBatchEndpoint = "/audit",
                    syncIntervalSeconds = 60,
                ),
            serverTechnicianKeysEnabled = true,
            serverTimeUtc = "2026-07-27T10:00:00.000Z",
            signingPublicKeys = null,
            revocationEpoch = 0,
        )
        coordinator.onDisconnect()

        assertTrue(stateDao.getState()?.hasTrustedAllowlistSync == true)
    }
}

class MvpTelemetryWebSocketManagerDisconnectLifecycleTest {
    @Test
    fun `disconnect clears capabilities and stops periodic jobs`() =
        runTest {
            val outboxDrain = mockk<MachineOutboxDrainCoordinator>(relaxed = true)
            val technicianCoordinator = mockk<TechnicianKeySessionCoordinator>(relaxed = true)
            val manager =
                MvpTelemetryWebSocketManager(
                    appScope = this,
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
