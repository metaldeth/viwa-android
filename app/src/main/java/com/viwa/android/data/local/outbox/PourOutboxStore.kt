package com.viwa.android.data.local.outbox



import com.viwa.android.data.remote.telemetry.v3.TelemetryPourMessageCodec

import com.viwa.android.domain.telemetry.PourEventSnapshot

import java.util.UUID

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.serialization.encodeToString

import kotlinx.serialization.json.Json



/** Durable `telemetry.pour.report` outbox — online subscription flavored/plain hold pours. */

@Singleton

class PourOutboxStore

@Inject

constructor(

    private val outboxStore: MachineOutboxStore,

) {

    private val json =

        Json {

            ignoreUnknownKeys = true

            encodeDefaults = true

        }



    suspend fun enqueuePourReport(pour: PourEventSnapshot): MachineOutboxStore.EnqueueResult {

        val existing =

            outboxStore.findByKindAndIdempotencyKey(

                MachineOutboxKind.TELEMETRY_POUR_REPORT,

                pour.requestUuid,

            )

        if (existing != null) {

            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)

        }

        val payloadJson = json.encodeToString(TelemetryPourMessageCodec.encodePayload(pour))

        val now = System.currentTimeMillis()

        val row =

            MachineOutboxEntryEntity(

                localId = UUID.randomUUID().toString(),

                kind = MachineOutboxKind.TELEMETRY_POUR_REPORT.wireValue,

                idempotencyKey = pour.requestUuid,

                messageId = UUID.randomUUID().toString(),

                payloadJson = payloadJson,

                status = MachineOutboxStatus.PENDING.name,

                attempts = 0,

                wsAckFailures = 0,

                nextRetryAtMs = now,

                lastError = null,

                sessionGenerationAtSend = null,

                createdAtMs = now,

                ackedAtMs = null,

                inFlightSinceMs = null,

            )

        return outboxStore.enqueueRaw(row)

    }

}


