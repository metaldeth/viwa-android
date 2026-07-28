package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence
import com.viwa.android.data.local.outbox.MachineOutboxStatus
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator
import com.viwa.android.data.local.sales.PendingSale
import com.viwa.android.data.local.sales.SalesOutboxStore
import com.viwa.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetrySalesSyncCoordinatorTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var outboxStore: SalesOutboxStore
    private lateinit var machineOutboxStore: MachineOutboxStore
    private lateinit var wsManager: MvpTelemetryWebSocketManager
    private lateinit var drainCoordinator: MachineOutboxDrainCoordinator
    private lateinit var coordinator: TelemetrySalesSyncCoordinator

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        val config = mockk<ConfigRepository>(relaxed = true)
        machineOutboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = config,
                migrator = PendingSalesOutboxMigrator(persistence, config),
            )
        outboxStore = SalesOutboxStore(machineOutboxStore)
        wsManager = mockk(relaxed = true)
        every { wsManager.currentSessionGeneration() } returns 1L
        every { wsManager.fsmPhase() } returns TelemetryConnectionPhase.Active
        drainCoordinator = mockk(relaxed = true)
        coordinator =
            TelemetrySalesSyncCoordinator(
                outboxStore = outboxStore,
                machineOutboxStore = machineOutboxStore,
                drainCoordinator = drainCoordinator,
                wsManager = wsManager,
            )
        coEvery { wsManager.sendEnvelope(any(), any()) } returns Result.success("msg-id")
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg-id")
    }

    @Test
    fun `enqueueAndTrySend keeps pending sale when ws send fails`() = runTest {
        coEvery { drainCoordinator.drain(any(), any()) } coAnswers {
            val row = persistence.allRows().single()
            machineOutboxStore.markWsSendFailure(row, "offline")
        }
        coordinator.enqueueAndTrySend(sampleSale())
        assertEquals(1, outboxStore.listPending(nowMillis = Long.MAX_VALUE).size)
    }

    @Test
    fun `enqueueAndTrySend does not mark sent on ws send alone`() = runTest {
        coEvery { drainCoordinator.onEnqueue() } coAnswers {
            val row = persistence.allRows().single()
            machineOutboxStore.markInFlight(row, sessionGeneration = 1L)
        }
        coordinator.enqueueAndTrySend(sampleSale())
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)
        assertNull(persistence.allRows().single().ackedAtMs)
    }

    @Test
    fun `flushPending triggers drain coordinator`() = runTest {
        outboxStore.enqueue(sampleSale())
        coordinator.flushPending()
        coVerify(exactly = 1) { drainCoordinator.drain("manual-flush", 1L) }
    }

    @Test
    fun `onWebSocketHello triggers session active drain`() = runTest {
        coordinator.onWebSocketHello()
        coVerify(exactly = 1) { drainCoordinator.onSessionActive(1L, reason = "hello") }
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
}
