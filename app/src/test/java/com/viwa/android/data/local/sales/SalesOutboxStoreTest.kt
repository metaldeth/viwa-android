package com.viwa.android.data.local.sales

import com.viwa.android.data.local.outbox.CommerceOutboxStore
import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStatus
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator
import com.viwa.android.data.local.db.JsonStoreKeys
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

class SalesOutboxStoreTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var store: SalesOutboxStore

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        configRepository = FakeConfigRepository()
        val machineOutboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = configRepository,
                migrator = PendingSalesOutboxMigrator(persistence, configRepository),
            )
        store = SalesOutboxStore(machineOutboxStore, CommerceOutboxStore(machineOutboxStore))
    }

    @Test
    fun `enqueue persists pending sale in room outbox`() = runTest {
        val sale = sampleSale(saleId = "sale-1")
        store.enqueue(sale)
        assertEquals(1, persistence.allRows().size)
        assertEquals("sale-1", persistence.allRows().first().idempotencyKey)
        assertEquals(MachineOutboxKind.TELEMETRY_PAID_COMPLETE.wireValue, persistence.allRows().first().kind)
    }

    @Test
    fun `load decodes legacy pending sales without concentrationRatio as null`() = runTest {
        store.enqueue(
            PendingSale(
                saleId = "legacy-sale",
                soldAt = "2026-07-20T12:00:00.000Z",
                drinkId = 20,
                volumeMl = 300,
                amountRub = 150.0,
                payMethod = "CARD",
            ),
        )
        val pending = store.listPending(nowMillis = Long.MAX_VALUE)
        assertEquals(1, pending.size)
        assertNull(pending.first().concentrationRatio)
    }

    @Test
    fun `markSent removes sale from pending list`() = runTest {
        store.enqueue(sampleSale(saleId = "sale-1"))
        store.markSent("sale-1")
        assertTrue(store.listPending().isEmpty())
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
    }

    @Test
    fun `bumpAttempt applies retry backoff schedule`() = runTest {
        store.enqueue(sampleSale(saleId = "sale-1"))
        store.bumpAttempt("sale-1", nowMillis = 0L)
        val row = persistence.allRows().single()
        assertEquals(MachineOutboxStatus.PENDING.name, row.status)
        assertTrue(row.attempts >= 1)
    }

    @Test
    fun `retryDelayMillis uses full jitter capped schedule`() {
        assertTrue(store.retryDelayMillis(1) in 0..1_000L)
        assertTrue(store.retryDelayMillis(5) in 0..30_000L)
        assertTrue(store.retryDelayMillis(99) in 0..30_000L)
    }

    @Test
    fun `jsonstore migration skips legacy pending sales under telemetry v3`() = runTest {
        val legacyJson =
            """
            [
              {
                "saleId": "legacy-sale",
                "soldAt": "2026-07-20T12:00:00.000Z",
                "drinkId": 20,
                "volumeMl": 200,
                "amountRub": 150.0,
                "payMethod": "CARD"
              }
            ]
            """.trimIndent()
        configRepository.setJson(JsonStoreKeys.PENDING_SALES, legacyJson)
        store.enqueue(sampleSale("new"))
        assertEquals(1, persistence.allRows().size)
        assertNotNull(configRepository.get(JsonStoreKeys.OUTBOX_PENDING_SALES_IMPORTED))
    }

    private fun sampleSale(saleId: String): PendingSale =
        PendingSale(
            saleId = saleId,
            soldAt = "2026-07-20T12:00:00.000Z",
            drinkId = 20,
            volumeMl = 200,
            amountRub = 150.0,
            payMethod = "CARD",
            concentrationRatio = 0.9,
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
