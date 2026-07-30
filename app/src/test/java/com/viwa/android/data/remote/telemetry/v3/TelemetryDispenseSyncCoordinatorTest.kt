package com.viwa.android.data.remote.telemetry.mvp



import com.viwa.android.data.local.outbox.CommerceOutboxStore

import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence

import com.viwa.android.data.local.outbox.MachineOutboxKind

import com.viwa.android.data.local.outbox.MachineOutboxStore

import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator

import com.viwa.android.data.local.outbox.PourOutboxStore

import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator

import com.viwa.android.domain.model.customer.DrinkConcentration

import com.viwa.android.domain.model.customer.DrinkDosage

import com.viwa.android.domain.telemetry.DispenseTelemetryFactory

import com.viwa.android.data.repository.ConfigRepository

import io.mockk.coEvery

import io.mockk.coVerify

import io.mockk.mockk

import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals

import org.junit.Before

import org.junit.Test



class TelemetryDispenseSyncCoordinatorTest {

    private lateinit var persistence: FakeMachineOutboxPersistence

    private lateinit var outboxStore: MachineOutboxStore

    private lateinit var pourOutboxStore: PourOutboxStore

    private lateinit var commerceOutboxStore: CommerceOutboxStore

    private lateinit var drainCoordinator: MachineOutboxDrainCoordinator

    private lateinit var wsManager: MvpTelemetryWebSocketManager

    private lateinit var coordinator: TelemetryDispenseSyncCoordinator



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

        pourOutboxStore = PourOutboxStore(outboxStore)

        commerceOutboxStore = CommerceOutboxStore(outboxStore)

        drainCoordinator = mockk(relaxed = true)

        wsManager = mockk(relaxed = true)

        coordinator =

            TelemetryDispenseSyncCoordinator(

                pourOutboxStore = pourOutboxStore,

                commerceOutboxStore = commerceOutboxStore,

                machineOutboxStore = outboxStore,

                drainCoordinator = drainCoordinator,

                wsManager = wsManager,

            )

    }



    @Test

    fun `enqueuePourReport writes telemetry pour kind and triggers drain`() = runTest {

        val pour =

            DispenseTelemetryFactory.flavoredPourEvent(

                requestUuid = "pour-uuid",

                volumeMl = 300,

                productId = "prod",

                productNameSnapshot = "Test",

                concentration = DrinkConcentration.Standard,

                dosage = DrinkDosage(0.5, 300, 30.0, 270.0),

                clientId = "client",

            )

        coordinator.enqueuePourReport(pour)

        assertEquals(MachineOutboxKind.TELEMETRY_POUR_REPORT.wireValue, persistence.allRows().single().kind)

        coVerify { drainCoordinator.onEnqueue() }

    }



    @Test

    fun `enqueuePaidComplete writes paid complete kind`() = runTest {

        val paid =

            DispenseTelemetryFactory.paidComplete(

                transactionId = "tx-uuid",

                requestUuid = "pour-uuid",

                volumeMl = 300,

                amountRub = 120.0,

                payMethod = "CARD",

                productId = "prod",

                productNameSnapshot = "Test",

                concentration = DrinkConcentration.Standard,

                dosage = DrinkDosage(0.5, 300, 30.0, 270.0),

            )

        coordinator.enqueuePaidComplete(paid)

        assertEquals(MachineOutboxKind.TELEMETRY_PAID_COMPLETE.wireValue, persistence.allRows().single().kind)

    }

}


