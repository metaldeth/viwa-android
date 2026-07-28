package com.viwa.android.data.local.sales

import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.OutboxRetryPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Legacy facade over [MachineOutboxStore] — keeps [PendingSale] API for preparing/sales path.
 * Durable storage is Room `machine_outbox`; JsonStore `pending_sales` is import-only.
 */
@Singleton
class SalesOutboxStore
@Inject
constructor(
    private val machineOutboxStore: MachineOutboxStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enqueue(sale: PendingSale) {
        machineOutboxStore.enqueueSale(sale)
    }

    suspend fun listPending(nowMillis: Long = System.currentTimeMillis()): List<PendingSale> =
        machineOutboxStore
            .listDrainable()
            .filter { row ->
                row.kind == MachineOutboxKind.SALE_REPORT.wireValue &&
                    row.nextRetryAtMs <= nowMillis
            }.mapNotNull { row -> row.toPendingSale() }

    suspend fun markSent(saleId: String) {
        machineOutboxStore.markAcked(
            idempotencyKey = saleId,
            kind = MachineOutboxKind.SALE_REPORT,
        )
    }

    suspend fun bumpAttempt(saleId: String, nowMillis: Long = System.currentTimeMillis()) {
        val entry =
            machineOutboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.SALE_REPORT,
                saleId,
            ) ?: return
        machineOutboxStore.markWsSendFailure(entry, "MANUAL_BUMP")
    }

    internal fun retryDelayMillis(attempts: Int): Long = OutboxRetryPolicy.nextRetryDelayMs(attempts)

    private fun com.viwa.android.data.local.outbox.MachineOutboxEntryEntity.toPendingSale(): PendingSale? =
        runCatching {
            val payload = json.parseToJsonElement(payloadJson).jsonObject
            PendingSale(
                saleId = payload["saleId"]?.jsonPrimitive?.content ?: idempotencyKey,
                soldAt = payload["soldAt"]?.jsonPrimitive?.content.orEmpty(),
                drinkId = payload["drinkId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                volumeMl = payload["volumeMl"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                amountRub = payload["amountRub"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                payMethod = payload["payMethod"]?.jsonPrimitive?.content.orEmpty(),
                concentrationRatio = payload["concentrationRatio"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                attempts = attempts,
                nextRetryAtMillis = nextRetryAtMs,
                status = PendingSaleStatus.PENDING,
            )
        }.getOrNull()

    companion object {
        val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    }
}
