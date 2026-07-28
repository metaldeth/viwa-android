package com.viwa.android.data.remote.telemetry.v3

import com.viwa.android.data.local.outbox.CommerceOutboxStore
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.PourOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.domain.telemetry.PaidCompleteSnapshot
import com.viwa.android.domain.telemetry.PourEventSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class TelemetryDispenseSyncCoordinator
@Inject
constructor(
    private val pourOutboxStore: PourOutboxStore,
    private val commerceOutboxStore: CommerceOutboxStore,
    private val machineOutboxStore: MachineOutboxStore,
    private val drainCoordinator: MachineOutboxDrainCoordinator,
    private val wsManager: MvpTelemetryWebSocketManager,
) {
    suspend fun enqueuePourReport(pour: PourEventSnapshot) {
        pourOutboxStore.enqueuePourReport(pour)
        drainCoordinator.onEnqueue()
    }

    suspend fun enqueuePaidComplete(paid: PaidCompleteSnapshot) {
        commerceOutboxStore.enqueuePaidComplete(paid)
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

    suspend fun handlePourAck(requestUuid: String): Boolean =
        machineOutboxStore.markAcked(
            idempotencyKey = requestUuid,
            kind = MachineOutboxKind.TELEMETRY_POUR_REPORT,
        ).also { ok ->
            if (ok) Timber.i("TelemetryDispense: telemetry.pour.report acked requestUuid=$requestUuid")
        }

    suspend fun handlePaidCompleteAck(transactionId: String): Boolean =
        machineOutboxStore.markAcked(
            idempotencyKey = transactionId,
            kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE,
        ).also { ok ->
            if (ok) Timber.i("TelemetryDispense: telemetry.paid.complete acked transactionId=$transactionId")
        }
}
