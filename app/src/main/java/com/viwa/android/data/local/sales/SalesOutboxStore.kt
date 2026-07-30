package com.viwa.android.data.local.sales



import com.viwa.android.data.local.outbox.CommerceOutboxStore

import com.viwa.android.data.local.outbox.MachineOutboxKind

import com.viwa.android.data.local.outbox.MachineOutboxStore

import com.viwa.android.data.local.outbox.OutboxRetryPolicy

import com.viwa.android.domain.model.customer.DrinkConcentration

import com.viwa.android.domain.model.customer.DrinkDosage

import com.viwa.android.domain.telemetry.DispenseTelemetryFactory

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.serialization.json.Json

import kotlinx.serialization.json.jsonObject

import kotlinx.serialization.json.jsonPrimitive



/** Test/legacy facade — maps [PendingSale] to telemetry v3 paid-complete outbox rows. */

@Singleton

class SalesOutboxStore

@Inject

constructor(

    private val machineOutboxStore: MachineOutboxStore,

    private val commerceOutboxStore: CommerceOutboxStore,

) {

    private val json = Json { ignoreUnknownKeys = true }



    suspend fun enqueue(sale: PendingSale) {

        val requestUuid = DispenseTelemetryFactory.newStableUuid()

        val dosage = DrinkDosage(conversionFactor = 0.5, drinkVolume = 300, product = 30.0, water = 270.0)

        val snapshot =

            DispenseTelemetryFactory.paidComplete(

                transactionId = sale.saleId,

                requestUuid = requestUuid,

                volumeMl = sale.volumeMl.let { if (it == 300 || it == 700) it else 300 },

                amountRub = sale.amountRub,

                payMethod = sale.payMethod,

                productId = "legacy-${sale.drinkId}",

                productNameSnapshot = "legacy-${sale.drinkId}",

                concentration = DrinkConcentration.Standard,

                dosage = dosage,

                occurredAt = sale.soldAt,

            )

        commerceOutboxStore.enqueuePaidComplete(snapshot)

    }



    suspend fun listPending(nowMillis: Long = System.currentTimeMillis()): List<PendingSale> =

        machineOutboxStore

            .listDrainable()

            .filter { row ->

                row.kind == MachineOutboxKind.TELEMETRY_PAID_COMPLETE.wireValue &&

                    row.nextRetryAtMs <= nowMillis

            }.mapNotNull { row -> row.toPendingSale() }



    suspend fun markSent(saleId: String) {

        machineOutboxStore.markAcked(

            idempotencyKey = saleId,

            kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE,

        )

    }



    suspend fun bumpAttempt(saleId: String, nowMillis: Long = System.currentTimeMillis()) {

        val entry =

            machineOutboxStore.findByKindAndIdempotencyKey(

                MachineOutboxKind.TELEMETRY_PAID_COMPLETE,

                saleId,

            ) ?: return

        machineOutboxStore.markWsSendFailure(entry, "MANUAL_BUMP")

    }



    internal fun retryDelayMillis(attempts: Int): Long = OutboxRetryPolicy.nextRetryDelayMs(attempts)



    private fun com.viwa.android.data.local.outbox.MachineOutboxEntryEntity.toPendingSale(): PendingSale? =

        runCatching {

            val payload = json.parseToJsonElement(payloadJson).jsonObject

            val amountKopecks = payload["amountKopecks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            PendingSale(

                saleId = payload["transactionId"]?.jsonPrimitive?.content ?: idempotencyKey,

                soldAt = payload["occurredAt"]?.jsonPrimitive?.content.orEmpty(),

                drinkId = 0,

                volumeMl = payload["volumeMl"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,

                amountRub = amountKopecks / 100.0,

                payMethod = payload["payMethod"]?.jsonPrimitive?.content.orEmpty(),

                concentrationRatio = null,

                attempts = attempts,

                nextRetryAtMillis = nextRetryAtMs,

                status = PendingSaleStatus.PENDING,

            )

        }.getOrNull()



    companion object {

        val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    }

}


