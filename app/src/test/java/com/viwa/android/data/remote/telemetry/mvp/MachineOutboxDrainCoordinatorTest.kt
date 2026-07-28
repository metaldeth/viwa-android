package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.CommerceOutboxStore
import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStatus
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.OutboxFeatureFlags
import com.viwa.android.data.local.outbox.OutboxRetryPolicy
import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator
import com.viwa.android.data.local.outbox.TestOutboxFixtures
import com.viwa.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MachineOutboxDrainCoordinatorTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var outboxStore: MachineOutboxStore
    private lateinit var commerceOutboxStore: CommerceOutboxStore
    private lateinit var wsManager: MvpTelemetryWebSocketManager
    private lateinit var apiClient: MvpTelemetryApiClient
    private lateinit var bearerProvider: MachineOutboxBearerTokenProvider
    private lateinit var coordinator: MachineOutboxDrainCoordinator

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        val config = mockk<ConfigRepository>(relaxed = true)
        outboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = config,
                migrator = PendingSalesOutboxMigrator(persistence, config),
            )
        commerceOutboxStore = CommerceOutboxStore(outboxStore)
        wsManager = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        bearerProvider = mockk(relaxed = true)
        every { wsManager.fsmPhase() } returns TelemetryConnectionPhase.Active
        every { wsManager.currentSessionGeneration() } returns 1L
        every { wsManager.isNetworkValidated() } returns true
        coordinator =
            MachineOutboxDrainCoordinator(
                outboxStore = outboxStore,
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                apiClient = apiClient,
                bearerTokenProvider = bearerProvider,
                appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
    }

    @Test
    fun `ws send keeps entry in flight without ack`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg")
        coordinator.drain("test", sessionGeneration = 1L)
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)
    }

    @Test
    fun `partial REST batch results update rows independently`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-2")
        val rows = persistence.allRows()
        val sent = buildList {
            for (row in rows) {
                add(checkNotNull(outboxStore.markInFlight(row, 1L)) { "expected PENDING row for ${row.idempotencyKey}" })
            }
        }
        val response =
            MachineOutboxBatchResponseDto(
                batchId = "batch-1",
                results =
                    listOf(
                        MachineOutboxItemResultDto(
                            messageId = sent[0].messageId,
                            status = "acked",
                        ),
                        MachineOutboxItemResultDto(
                            messageId = sent[1].messageId,
                            status = "rejected",
                            code = "INVALID_PAYLOAD",
                        ),
                    ),
            )
        coordinator.applyBatchResults(sent, response)
        val after = persistence.allRows().associateBy { it.idempotencyKey }
        assertNull(after["sale-1"])
        assertEquals(MachineOutboxStatus.REJECTED.name, after["sale-2"]!!.status)
        assertEquals(1, persistence.allRows().size)
    }

    @Test
    fun `REST batch acked rows are purged immediately`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        val row = persistence.allRows().single()
        val sent = listOf(outboxStore.markInFlight(row, 1L)!!)
        val response =
            MachineOutboxBatchResponseDto(
                batchId = "batch-1",
                results =
                    listOf(
                        MachineOutboxItemResultDto(
                            messageId = sent[0].messageId,
                            status = "acked",
                        ),
                    ),
            )
        coordinator.applyBatchResults(sent, response)
        assertEquals(0, persistence.allRows().count { it.status == MachineOutboxStatus.ACKED.name })
    }

    @Test
    fun `purgeAckedOlderThan removes only terminal acked rows`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-2")
        val rows = persistence.allRows()
        outboxStore.markAcked(messageId = rows[0].messageId, kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE)
        outboxStore.markServerError(rows[1], "INVALID_PAYLOAD", "bad")
        val purged = outboxStore.purgeAckedOlderThan(retentionMs = 0L, nowMs = System.currentTimeMillis() + 1)
        assertEquals(1, purged)
        assertEquals(1, persistence.allRows().size)
        assertEquals(MachineOutboxStatus.REJECTED.name, persistence.allRows().single().status)
    }

    @Test
    fun `REST fallback skipped when feature flag disabled`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        every { wsManager.fsmPhase() } returns TelemetryConnectionPhase.Backoff
        every { wsManager.outboxBatchCapability() } returns
            MvpOutboxBatchCapabilityDto(
                endpoint = "https://tl.example.com/api/v1/machines/outbox/batch",
                maxBatchSize = 50,
                supportedKinds = listOf("telemetry.paid.complete"),
            )
        coordinator.drain("test", sessionGeneration = 1L)
        assertTrue(OutboxFeatureFlags.FEATURE_OUTBOX_REST_SYNC.not())
        assertEquals(MachineOutboxStatus.PENDING.name, persistence.allRows().single().status)
    }
}
