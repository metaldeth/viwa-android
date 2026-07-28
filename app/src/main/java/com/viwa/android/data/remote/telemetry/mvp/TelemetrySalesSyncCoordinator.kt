package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.sales.PendingSale
import com.viwa.android.data.local.sales.SalesOutboxStore
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class TelemetrySalesSyncCoordinator
@Inject
constructor(
    private val outboxStore: SalesOutboxStore,
    private val machineOutboxStore: MachineOutboxStore,
    private val drainCoordinator: MachineOutboxDrainCoordinator,
    private val wsManager: MvpTelemetryWebSocketManager,
) {
    suspend fun enqueueAndTrySend(sale: PendingSale) {
        outboxStore.enqueue(sale)
        drainCoordinator.onEnqueue()
    }

    suspend fun onWebSocketHello() {
        val gen = wsManager.currentSessionGeneration()
        drainCoordinator.onSessionActive(gen, reason = "hello")
    }

    suspend fun flushPending() {
        val gen = wsManager.currentSessionGeneration()
        drainCoordinator.drain("manual-flush", gen)
    }

    suspend fun handleSaleAck(saleId: String): Boolean =
        machineOutboxStore.markAcked(
            idempotencyKey = saleId,
            kind = com.viwa.android.data.local.outbox.MachineOutboxKind.SALE_REPORT,
        ).also { ok ->
            if (ok) Timber.i("TelemetrySalesSync: sale.report acked saleId=$saleId")
        }
}
