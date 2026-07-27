package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.sales.PendingSale
import com.viwa.android.data.local.sales.SalesOutboxStore
import com.viwa.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class TelemetrySalesSyncCoordinatorTest {
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var outboxStore: SalesOutboxStore
    private lateinit var wsManager: MvpTelemetryWebSocketManager
    private lateinit var coordinator: TelemetrySalesSyncCoordinator

    @Before
    fun setUp() {
        configRepository = FakeConfigRepository()
        outboxStore = SalesOutboxStore(configRepository)
        wsManager = mockk(relaxed = true)
        coordinator =
            TelemetrySalesSyncCoordinator(
                outboxStore = outboxStore,
                wsManager = wsManager,
            )
        coEvery { wsManager.sendEnvelope(any(), any()) } returns Result.success("msg-id")
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg-id")
    }

    @Test
    fun `enqueueAndTrySend keeps pending sale when ws send fails`() = runTest {
        // given
        coEvery { wsManager.sendEnvelope("sale.report", any()) } returns Result.failure(IllegalStateException("offline"))
        val sale = sampleSale()

        // when
        coordinator.enqueueAndTrySend(sale)

        // then
        val raw = configRepository.getJson(JsonStoreKeys.PENDING_SALES)
        assertNotNull(raw)
        assertEquals(1, outboxStore.listPending(nowMillis = Long.MAX_VALUE).size)
    }

    @Test
    fun `enqueueAndTrySend marks sent when ws send succeeds`() = runTest {
        // given
        val sale = sampleSale()

        // when
        coordinator.enqueueAndTrySend(sale)

        // then
        assertEquals(0, outboxStore.listPending(nowMillis = Long.MAX_VALUE).size)
    }

    @Test
    fun `flushPending sends sale report envelope`() = runTest {
        // given
        val payloadSlot = slot<JsonObject>()
        coEvery { wsManager.sendEnvelope("sale.report", capture(payloadSlot)) } returns Result.success("test-message-id")
        outboxStore.enqueue(sampleSale())

        // when
        coordinator.flushPending()

        // then
        coVerify(exactly = 1) { wsManager.sendEnvelope("sale.report", any()) }
        assertEquals("sale-1", payloadSlot.captured["saleId"]?.toString()?.trim('"'))
        assertEquals("1.1", payloadSlot.captured["concentrationRatio"]?.toString()?.trim('"'))
    }

    private fun sampleSale(): PendingSale =
        PendingSale(
            saleId = "sale-1",
            soldAt = "2026-07-20T12:00:00.000Z",
            drinkId = 20,
            volumeMl = 200,
            amountRub = 150.0,
            payMethod = "CARD",
            concentrationRatio = 1.1,
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
