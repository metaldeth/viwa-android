package com.viwa.android.data.local.outbox

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.sales.PendingSale
import com.viwa.android.data.local.sales.PendingSaleStatus
import com.viwa.android.data.repository.ConfigRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MachineOutboxStoreTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var store: MachineOutboxStore
    private lateinit var commerceOutboxStore: CommerceOutboxStore

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        configRepository = FakeConfigRepository()
        store =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = configRepository,
                migrator =
                    PendingSalesOutboxMigrator(
                        persistence = persistence,
                        configRepository = configRepository,
                    ),
            )
        commerceOutboxStore = CommerceOutboxStore(store)
    }

    @Test
    fun `duplicate enqueue is idempotent by kind and idempotencyKey`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        val firstRow = persistence.allRows().single()
        val duplicate =
            commerceOutboxStore.enqueuePaidComplete(
                com.viwa.android.domain.telemetry.DispenseTelemetryFactory.paidComplete(
                    transactionId = "sale-1",
                    requestUuid = "pour-dup",
                    volumeMl = 300,
                    amountRub = 150.0,
                    payMethod = "CARD",
                    productId = "prod",
                    productNameSnapshot = "Test",
                    concentration = com.viwa.android.domain.model.customer.DrinkConcentration.Standard,
                    dosage = com.viwa.android.domain.model.customer.DrinkDosage(0.5, 300, 30.0, 270.0),
                ),
            )
        assertTrue(duplicate is MachineOutboxStore.EnqueueResult.Duplicate)
        assertEquals(1, persistence.allRows().size)
        assertEquals(firstRow.localId, (duplicate as MachineOutboxStore.EnqueueResult.Duplicate).existingLocalId)
    }

    @Test
    fun `markInFlight then recover returns entry to PENDING`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 7L)
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)
        store.recoverInFlightToPending()
        assertEquals(MachineOutboxStatus.PENDING.name, persistence.allRows().single().status)
    }

    @Test
    fun `re-sending in-flight entry preserves original ack timeout clock`() = runTest {
        persistence.insert(
            MachineOutboxEntryEntity(
                localId = "local-in-flight",
                kind = MachineOutboxKind.TELEMETRY_POUR_REPORT.wireValue,
                idempotencyKey = "pour-in-flight",
                messageId = "message-in-flight",
                payloadJson = "{}",
                status = MachineOutboxStatus.IN_FLIGHT.name,
                attempts = 0,
                wsAckFailures = 0,
                nextRetryAtMs = 0L,
                lastError = null,
                sessionGenerationAtSend = 1L,
                createdAtMs = 1L,
                ackedAtMs = null,
                inFlightSinceMs = 123L,
            ),
        )

        store.markInFlight(persistence.allRows().single(), sessionGeneration = 2L)

        val updated = persistence.allRows().single()
        assertEquals(123L, updated.inFlightSinceMs)
        assertEquals(2L, updated.sessionGenerationAtSend)
    }

    @Test
    fun `ws send success does not mark acked`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val stillThere = persistence.allRows().single()
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, stillThere.status)
        assertNull(stillThere.ackedAtMs)
    }

    @Test
    fun `ack removes entry from pending drain list`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        store.markAcked(messageId = row.messageId, kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE)
        assertEquals(0, store.countPendingOrInFlight())
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
    }

    @Test
    fun `terminal server error marks REJECTED`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        val updated = store.markServerError(row, "INVALID_PAYLOAD", "bad payload")
        assertEquals(MachineOutboxStatus.REJECTED.name, updated.status)
    }

    @Test
    fun `retryable server error returns to PENDING with backoff`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        val updated = store.markServerError(row, "INTERNAL", "try again")
        assertEquals(MachineOutboxStatus.PENDING.name, updated.status)
        assertTrue(updated.nextRetryAtMs > 0)
    }

    @Test
    fun `attempts above 50 mark DEAD`() = runTest {
        persistence.insert(
            MachineOutboxEntryEntity(
                localId = "local-1",
                kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE.wireValue,
                idempotencyKey = "sale-1",
                messageId = "msg-1",
                payloadJson = "{}",
                status = MachineOutboxStatus.PENDING.name,
                attempts = 50,
                wsAckFailures = 0,
                nextRetryAtMs = 0L,
                lastError = null,
                sessionGenerationAtSend = null,
                createdAtMs = 1L,
                ackedAtMs = null,
                inFlightSinceMs = null,
            ),
        )
        val row = persistence.allRows().single()
        val updated = store.markWsSendFailure(row, "fail")
        assertEquals(MachineOutboxStatus.DEAD.name, updated.status)
    }

    @Test
    fun `legacy JsonStore import is skipped under telemetry v3`() = runTest {
        val legacy =
            listOf(
                PendingSale(
                    saleId = "legacy-1",
                    soldAt = "2026-07-20T12:00:00.000Z",
                    drinkId = 20,
                    volumeMl = 200,
                    amountRub = 150.0,
                    payMethod = "CARD",
                    status = PendingSaleStatus.PENDING,
                ),
            )
        configRepository.setJson(JsonStoreKeys.PENDING_SALES, Json.encodeToString(legacy))
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "new-sale")
        assertEquals(1, persistence.allRows().size)
        assertNotNull(configRepository.get(JsonStoreKeys.OUTBOX_PENDING_SALES_IMPORTED))
        assertNotNull(configRepository.getJson(JsonStoreKeys.PENDING_SALES))
    }

    @Test
    fun `ws ack timeout increments wsAckFailures`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore)
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val updated = store.markWsAckTimeout(row)
        assertEquals(1, updated.wsAckFailures)
        assertEquals(MachineOutboxStatus.PENDING.name, updated.status)
    }

    @Test
    fun `rotateMessageIdForRetry preserves idempotencyKey and payloadJson`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-rotate")
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val rotated = store.rotateMessageIdForRetry(persistence.allRows().single())
        assertEquals("sale-rotate", rotated.idempotencyKey)
        assertEquals(row.payloadJson, rotated.payloadJson)
        assertTrue(rotated.messageId != row.messageId)
        assertEquals(MachineOutboxStatus.PENDING.name, rotated.status)
        assertNull(rotated.inFlightSinceMs)
    }

    @Test
    fun `ws ack timeout rotates messageId for pour report`() = runTest {
        val pourStore = PourOutboxStore(store)
        TestOutboxFixtures.enqueueTestPour(pourStore, requestUuid = "pour-timeout-rotate")
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val updated = store.markWsAckTimeout(row)
        assertTrue(updated.messageId != row.messageId)
        assertEquals("pour-timeout-rotate", updated.idempotencyKey)
        assertEquals(row.payloadJson, updated.payloadJson)
        assertEquals(1, updated.wsAckFailures)
    }

    private class FakeConfigRepository : ConfigRepository {
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
