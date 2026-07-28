package com.viwa.android.data.local.outbox

import com.viwa.android.data.telemetry.loyalty.LoyaltyWaterUseRequest
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable `loyalty.water.use` outbox — online and offline promote path. */
@Singleton
class LoyaltyWaterOutboxStore
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun enqueueWaterUse(
        clientId: String,
        requestUuid: String,
        volumeMl: Int,
        drinkId: Int? = null,
        saleId: String? = null,
        isFree: Boolean = true,
        priceKopecks: Int = 0,
    ): MachineOutboxStore.EnqueueResult {
        val existing = outboxStore.findByKindAndIdempotencyKey(MachineOutboxKind.LOYALTY_WATER_USE, requestUuid)
        if (existing != null) {
            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)
        }
        val request =
            LoyaltyWaterUseRequest(
                clientId = clientId,
                requestUuid = requestUuid,
                volumeMl = volumeMl,
                drinkId = drinkId,
                ingredientId = drinkId,
                isFree = isFree,
                priceKopecks = priceKopecks,
            )
        val payloadJson = json.encodeToString(LoyaltyWsCodec.encodeWaterUse(request))
        val now = System.currentTimeMillis()
        val row =
            MachineOutboxEntryEntity(
                localId = UUID.randomUUID().toString(),
                kind = MachineOutboxKind.LOYALTY_WATER_USE.wireValue,
                idempotencyKey = requestUuid,
                messageId = UUID.randomUUID().toString(),
                payloadJson = payloadJson,
                status = MachineOutboxStatus.PENDING.name,
                attempts = 0,
                wsAckFailures = 0,
                nextRetryAtMs = now,
                lastError = if (saleId.isNullOrBlank()) null else "linkedSaleId=$saleId",
                sessionGenerationAtSend = null,
                createdAtMs = now,
                ackedAtMs = null,
                inFlightSinceMs = null,
            )
        return outboxStore.enqueueRaw(row)
    }
}
