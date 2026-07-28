package com.viwa.android.data.local.outbox

import com.viwa.android.data.remote.telemetry.v3.TelemetryPaidCompleteMessageCodec
import com.viwa.android.domain.telemetry.PaidCompleteSnapshot
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable `telemetry.paid.complete` outbox — atomic paid transaction + linked pour. */
@Singleton
class CommerceOutboxStore
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun enqueuePaidComplete(paid: PaidCompleteSnapshot): MachineOutboxStore.EnqueueResult {
        val existing =
            outboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.TELEMETRY_PAID_COMPLETE,
                paid.transactionId,
            )
        if (existing != null) {
            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)
        }
        val payloadJson = json.encodeToString(TelemetryPaidCompleteMessageCodec.encodePayload(paid))
        val now = System.currentTimeMillis()
        val row =
            MachineOutboxEntryEntity(
                localId = UUID.randomUUID().toString(),
                kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE.wireValue,
                idempotencyKey = paid.transactionId,
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
