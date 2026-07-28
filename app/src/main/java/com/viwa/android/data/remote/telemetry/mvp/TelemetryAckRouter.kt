package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.MachineOutboxEntryEntity
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

enum class AckRouteOutcome {
    HANDLED,
    ORPHAN,
}

/**
 * Table-driven ACK/error router (ADR-009). Outbox correlation takes precedence over payload heuristics.
 * Cells (`schemaHash`) and loyalty handlers remain compatible with pre-outbox behaviour.
 */
@Singleton
class TelemetryAckRouter
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
) {
    suspend fun routeAck(
        envelope: MvpWsEnvelopeDto,
        sessionGeneration: Long,
        cellsHandler: (suspend (JsonObject) -> Unit)?,
        loyaltyHandler: (suspend (String, JsonObject) -> Unit)?,
        technicianHandler: (suspend (String, JsonObject) -> Unit)? = null,
    ): AckRouteOutcome {
        val payload = envelope.payload?.jsonObject ?: return AckRouteOutcome.ORPHAN
        val correlation =
            envelope.correlationId
                ?: payload["correlationId"]?.jsonPrimitive?.content

        if (!correlation.isNullOrBlank()) {
            val byMessage = outboxStore.findByMessageId(correlation)
            if (byMessage != null) {
                return if (byMessage.sessionGenerationAtSend == sessionGeneration) {
                    outboxStore.markAcked(messageId = correlation)
                    AckRouteOutcome.HANDLED
                } else {
                    staleOutboxAck(correlation, sessionGeneration)
                }
            }
        }

        val requestUuid = payload["requestUuid"]?.jsonPrimitive?.content
        if (!requestUuid.isNullOrBlank()) {
            val entry =
                outboxStore.findByKindAndIdempotencyKey(
                    MachineOutboxKind.LOYALTY_WATER_USE,
                    requestUuid,
                )
            if (entry != null) {
                return if (entry.sessionGenerationAtSend == sessionGeneration) {
                    outboxStore.markAcked(
                        idempotencyKey = requestUuid,
                        kind = MachineOutboxKind.LOYALTY_WATER_USE,
                    )
                    AckRouteOutcome.HANDLED
                } else {
                    staleOutboxAck("requestUuid=$requestUuid", sessionGeneration)
                }
            }
        }

        val saleId = payload["saleId"]?.jsonPrimitive?.content
        if (!saleId.isNullOrBlank()) {
            val entry =
                outboxStore.findByKindAndIdempotencyKey(
                    MachineOutboxKind.SALE_REPORT,
                    saleId,
                )
            if (entry != null) {
                return if (entry.sessionGenerationAtSend == sessionGeneration) {
                    outboxStore.markAcked(idempotencyKey = saleId, kind = MachineOutboxKind.SALE_REPORT)
                    AckRouteOutcome.HANDLED
                } else {
                    staleOutboxAck("saleId=$saleId", sessionGeneration)
                }
            }
        }

        if (payload.containsKey("schemaHash")) {
            cellsHandler?.invoke(payload)
            return AckRouteOutcome.HANDLED
        }

        val dailyRemaining = payload["dailyRemainingMl"]
        val volumeAfter = payload["volumeAfterMl"]
        if (dailyRemaining != null || volumeAfter != null) {
            if (!correlation.isNullOrBlank() && loyaltyHandler != null) {
                loyaltyHandler(correlation, payload)
                return AckRouteOutcome.HANDLED
            }
        }

        if (isTechnicianAckPayload(payload)) {
            if (!correlation.isNullOrBlank() && technicianHandler != null) {
                technicianHandler(correlation, payload)
                return AckRouteOutcome.HANDLED
            }
        }

        Timber.d("TelemetryAckRouter: orphan ack correlation=$correlation gen=$sessionGeneration")
        return AckRouteOutcome.ORPHAN
    }

    suspend fun routeError(
        envelope: MvpWsEnvelopeDto,
        sessionGeneration: Long,
        outboxErrorHandler: (suspend (MachineOutboxEntryEntity, String, String) -> Unit)?,
        loyaltyErrorHandler: (suspend (String?, String, String) -> Unit)?,
        technicianErrorHandler: (suspend (String?, String, String) -> Unit)? = null,
    ): AckRouteOutcome {
        val correlation = envelope.correlationId
        val payload = envelope.payload?.jsonObject ?: return AckRouteOutcome.ORPHAN
        val code = payload["code"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val message = payload["message"]?.jsonPrimitive?.content ?: code
        val entry = correlation?.let { outboxStore.findByMessageId(it) }
        if (entry != null) {
            if (entry.sessionGenerationAtSend != sessionGeneration) {
                return staleOutboxAck(correlation, sessionGeneration)
            }
            outboxErrorHandler?.invoke(entry, code, message)
            return AckRouteOutcome.HANDLED
        }
        if (isTechnicianErrorPayload(payload, code)) {
            technicianErrorHandler?.invoke(correlation, code, message)
            return AckRouteOutcome.HANDLED
        }
        loyaltyErrorHandler?.invoke(correlation, code, message)
        return AckRouteOutcome.ORPHAN
    }

    private fun staleOutboxAck(key: String, sessionGeneration: Long): AckRouteOutcome {
        Timber.d("TelemetryAckRouter: stale outbox ack key=$key gen=$sessionGeneration")
        return AckRouteOutcome.ORPHAN
    }

    private fun isTechnicianAckPayload(payload: JsonObject): Boolean =
        payload.containsKey("technicianKeyId") ||
            (payload.containsKey("requestedScope") && payload.containsKey("sessionToken"))

    private fun isTechnicianErrorPayload(payload: JsonObject, code: String): Boolean =
        payload.containsKey("requestedScope") || isTechnicianErrorCode(code)

    private fun isTechnicianErrorCode(code: String): Boolean =
        code in TECHNICIAN_ERROR_CODES

    private companion object {
        val TECHNICIAN_ERROR_CODES =
            setOf(
                "KEY_INVALID_FORMAT",
                "KEY_NOT_FOUND",
                "KEY_REVOKED",
                "KEY_EXPIRED",
                "KEY_MACHINE_DENIED",
                "KEY_SCOPE_DENIED",
            )
    }
}
