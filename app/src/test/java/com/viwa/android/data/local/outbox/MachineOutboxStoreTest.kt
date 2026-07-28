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
    }

    @Test
    fun `duplicate enqueue is idempotent by kind and idempotencyKey`() = runTest {
        val sale = sampleSale("sale-1")
        val first = store.enqueueSale(sale)
        val second = store.enqueueSale(sale)

        assertTrue(first is MachineOutboxStore.EnqueueResult.Inserted)
        assertTrue(second is MachineOutboxStore.EnqueueResult.Duplicate)
        assertEquals(1, persistence.allRows().size)
    }

    @Test
    fun `markInFlight then recover returns entry to PENDING`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 7L)
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)

        store.recoverInFlightToPending()
        assertEquals(MachineOutboxStatus.PENDING.name, persistence.allRows().single().status)
    }

    @Test
    fun `ws send success does not mark acked`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val stillThere = persistence.allRows().single()
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, stillThere.status)
        assertNull(stillThere.ackedAtMs)
    }

    @Test
    fun `ack removes entry from pending drain list`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        store.markAcked(messageId = row.messageId)
        assertEquals(0, store.countPendingOrInFlight())
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
    }

    @Test
    fun `terminal server error marks REJECTED`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        val updated = store.markServerError(row, "INVALID_PAYLOAD", "bad payload")
        assertEquals(MachineOutboxStatus.REJECTED.name, updated.status)
    }

    @Test
    fun `retryable server error returns to PENDING with backoff`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        val updated = store.markServerError(row, "INTERNAL", "try again")
        assertEquals(MachineOutboxStatus.PENDING.name, updated.status)
        assertTrue(updated.nextRetryAtMs > 0)
    }

    @Test
    fun `attempts above 50 mark DEAD`() = runTest {
        persistence.insert(
            com.viwa.android.data.local.outbox.MachineOutboxEntryEntity(
                localId = "local-1",
                kind = MachineOutboxKind.SALE_REPORT.wireValue,
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
    fun `import pending sales from JsonStore is idempotent`() = runTest {
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
        store.enqueueSale(sampleSale("new-sale"))
        assertEquals(2, persistence.allRows().size)
        assertNotNull(configRepository.get(JsonStoreKeys.OUTBOX_PENDING_SALES_IMPORTED))
        assertNotNull(configRepository.getJson(JsonStoreKeys.PENDING_SALES))

        val store2 =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = configRepository,
                migrator =
                    PendingSalesOutboxMigrator(
                        persistence = persistence,
                        configRepository = configRepository,
                    ),
            )
        store2.enqueueSale(sampleSale("another"))
        assertEquals(3, persistence.allRows().size)
    }

    @Test
    fun `ws ack timeout increments wsAckFailures`() = runTest {
        store.enqueueSale(sampleSale("sale-1"))
        val row = persistence.allRows().single()
        store.markInFlight(row, sessionGeneration = 1L)
        val updated = store.markWsAckTimeout(row)
        assertEquals(1, updated.wsAckFailures)
        assertEquals(MachineOutboxStatus.PENDING.name, updated.status)
    }

    private fun sampleSale(saleId: String): PendingSale =
        PendingSale(
            saleId = saleId,
            soldAt = "2026-07-20T12:00:00.000Z",
            drinkId = 20,
            volumeMl = 200,
            amountRub = 150.0,
            payMethod = "CARD",
        )

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
