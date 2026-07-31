package com.viwa.android.domain.telemetry

import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.hardware.controller.ViwaWaterCounterService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HoldPourTelemetryCoordinatorTest {
    private lateinit var waterCounter: ViwaWaterCounterService
    private lateinit var dispenseSync: TelemetryDispenseSyncCoordinator
    private lateinit var coordinator: HoldPourTelemetryCoordinator

    @Before
    fun setUp() {
        waterCounter = mockk(relaxed = true)
        dispenseSync = mockk(relaxed = true)
        coordinator = HoldPourTelemetryCoordinator(waterCounter, dispenseSync)
    }

    @Test
    fun `anonymous filtered water enqueues plain pour with null clientId`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 240
        val report = slot<PourEventSnapshot>()

        coordinator.beginHoldPourSession(
            clientId = null,
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Filtered,
            offlineMode = false,
            requestUuid = "anonymous-filtered",
        )
        val measured = coordinator.finalizeHoldPourSession()

        assertEquals(240, measured)
        coVerify(exactly = 1) { dispenseSync.enqueuePourReport(capture(report)) }
        assertNull(report.captured.clientId)
        assertEquals(PlainWaterType.FILTERED.wireValue, report.captured.plainWaterType)
    }

    @Test
    fun `identified filtered water enqueues unlimited plain pour online`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 240
        val report = slot<PourEventSnapshot>()

        coordinator.beginHoldPourSession(
            clientId = "client-1",
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Filtered,
            offlineMode = false,
            requestUuid = "filtered-request",
        )
        coordinator.finalizeHoldPourSession()

        coVerify(exactly = 1) { dispenseSync.enqueuePourReport(capture(report)) }
        assertEquals("client-1", report.captured.clientId)
        assertEquals(PlainWaterType.FILTERED.wireValue, report.captured.plainWaterType)
    }

    @Test
    fun `offline plain water uses machine outbox not entitlement ledger`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 180
        val report = slot<PourEventSnapshot>()

        coordinator.beginHoldPourSession(
            clientId = "client-filtered-offline",
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Filtered,
            offlineMode = true,
            requestUuid = "filtered-offline-request",
        )
        coordinator.finalizeHoldPourSession()

        coVerify(exactly = 1) { dispenseSync.enqueuePourReport(capture(report)) }
        assertEquals("filtered-offline-request", report.captured.requestUuid)
    }

    @Test
    fun `cold water reports measured plain pour with COLD wire type`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 315
        val report = slot<PourEventSnapshot>()

        coordinator.beginHoldPourSession(
            clientId = "client-2",
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Cold,
            offlineMode = false,
            requestUuid = "cold-request",
        )
        coordinator.finalizeHoldPourSession()

        coVerify(exactly = 1) { dispenseSync.enqueuePourReport(capture(report)) }
        assertEquals(PlainWaterType.COLD.wireValue, report.captured.plainWaterType)
    }

    @Test
    fun `sparkling water reports SPARKLING wire type`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 180
        val report = slot<PourEventSnapshot>()

        coordinator.beginHoldPourSession(
            clientId = "client-3",
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Sparkling,
            offlineMode = false,
            requestUuid = "sparkling-request",
        )
        coordinator.finalizeHoldPourSession()

        coVerify(exactly = 1) { dispenseSync.enqueuePourReport(capture(report)) }
        assertEquals(PlainWaterType.SPARKLING.wireValue, report.captured.plainWaterType)
    }

    @Test
    fun `zero ml emits no telemetry event`() = runTest {
        coEvery { waterCounter.endHoldPourSessionAndReset() } returns 0

        coordinator.beginHoldPourSession(
            clientId = "client-zero",
            machineId = "machine-1",
            plainWaterType = FlowWaterPourType.Filtered,
            offlineMode = true,
            requestUuid = "zero-request",
        )
        val measured = coordinator.finalizeHoldPourSession()

        assertEquals(0, measured)
        coVerify(exactly = 0) { dispenseSync.enqueuePourReport(any()) }
    }
}
