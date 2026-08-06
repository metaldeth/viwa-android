package com.viwa.android.data.local.outbox

import com.viwa.android.data.remote.telemetry.v3.TelemetryWaterUsageMessageCodec
import com.viwa.android.domain.telemetry.WaterUsageReportSnapshot
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable `machine.water.usage.report` outbox — absolute lifetime water total after each increment. */
@Singleton
class WaterUsageOutboxStore
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun enqueueWaterUsageReport(report: WaterUsageReportSnapshot): MachineOutboxStore.EnqueueResult {
        val idempotencyKey = report.reportedAt
        val existing =
            outboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.MACHINE_WATER_USAGE_REPORT,
                idempotencyKey,
            )
        if (existing != null) {
            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)
        }
        val payloadJson = json.encodeToString(TelemetryWaterUsageMessageCodec.encodePayload(report))
        val now = System.currentTimeMillis()
        val row =
            MachineOutboxEntryEntity(
                localId = UUID.randomUUID().toString(),
                kind = MachineOutboxKind.MACHINE_WATER_USAGE_REPORT.wireValue,
                idempotencyKey = idempotencyKey,
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
