package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.MachineOutboxEntryEntity
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStatus
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.cells.CellsContentReportAckSemantics
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.domain.telemetry.PourKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

enum class AckRouteOutcome {
    HANDLED,
    ORPHAN,
    /** Pour dedup ack without domain persistence proof — transport messageId must rotate. */
    UNPROVEN_POUR_DEDUP,
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
    private val recipeCodec: RecipeMessageCodec,
) {
    suspend fun routeAck(
        envelope: MvpWsEnvelopeDto,
        sessionGeneration: Long,
        cellsHandler: (suspend (JsonObject) -> Unit)?,
        loyaltyHandler: (suspend (String, JsonObject) -> Unit)?,
        technicianHandler: (suspend (String, JsonObject) -> Unit)? = null,
        pourBalanceHandler: (suspend (JsonObject) -> Unit)? = null,
        cellsContentAckHandler: (suspend (String, JsonObject) -> Unit)? = null,
        recipeAckHandler: (suspend (String, JsonObject) -> Unit)? = null,
        onUnprovenPourDedupAck: (suspend (MachineOutboxEntryEntity) -> Unit)? = null,
    ): AckRouteOutcome {
        val payload = envelope.payload?.jsonObject ?: return AckRouteOutcome.ORPHAN
        val correlation =
            envelope.correlationId
                ?: payload["correlationId"]?.jsonPrimitive?.content

        if (!correlation.isNullOrBlank()) {
            val byMessage = outboxStore.findByMessageId(correlation)
            if (byMessage != null) {
                val kind = MachineOutboxKind.fromWire(byMessage.kind) ?: return AckRouteOutcome.ORPHAN
                return routeMatchedOutboxAck(
                    entry = byMessage,
                    kind = kind,
                    sessionGeneration = sessionGeneration,
                    payload = payload,
                    ackKey = correlation,
                    markAcked = { outboxStore.markAcked(messageId = correlation, kind = kind) },
                    pourBalanceHandler = pourBalanceHandler,
                    onUnprovenPourDedupAck = onUnprovenPourDedupAck,
                )
            }
        }

        val requestUuid = payload["requestUuid"]?.jsonPrimitive?.content
        if (!requestUuid.isNullOrBlank()) {
            val entry =
                outboxStore.findByKindAndIdempotencyKey(
                    MachineOutboxKind.TELEMETRY_POUR_REPORT,
                    requestUuid,
                )
            if (entry != null) {
                return routeMatchedOutboxAck(
                    entry = entry,
                    kind = MachineOutboxKind.TELEMETRY_POUR_REPORT,
                    sessionGeneration = sessionGeneration,
                    payload = payload,
                    ackKey = "requestUuid=$requestUuid",
                    markAcked = {
                        outboxStore.markAcked(
                            idempotencyKey = requestUuid,
                            kind = MachineOutboxKind.TELEMETRY_POUR_REPORT,
                        )
                    },
                    pourBalanceHandler = pourBalanceHandler,
                    onUnprovenPourDedupAck = onUnprovenPourDedupAck,
                )
            }
        }

        val transactionId = payload["transactionId"]?.jsonPrimitive?.content
        if (!transactionId.isNullOrBlank()) {
            val entry =
                outboxStore.findByKindAndIdempotencyKey(
                    MachineOutboxKind.TELEMETRY_PAID_COMPLETE,
                    transactionId,
                )
            if (entry != null) {
                return if (entry.sessionGenerationAtSend == sessionGeneration) {
                    outboxStore.markAcked(
                        idempotencyKey = transactionId,
                        kind = MachineOutboxKind.TELEMETRY_PAID_COMPLETE,
                    )
                    AckRouteOutcome.HANDLED
                } else {
                    staleOutboxAck("transactionId=$transactionId", sessionGeneration)
                }
            }
        }

        if (payload.containsKey("schemaHash")) {
            cellsHandler?.invoke(payload)
            return AckRouteOutcome.HANDLED
        }

        if (
            !correlation.isNullOrBlank() &&
            recipeAckHandler != null &&
            recipeCodec.isRecipeCommandAckPayload(payload)
        ) {
            recipeAckHandler(correlation, payload)
            return AckRouteOutcome.HANDLED
        }

        if (
            !correlation.isNullOrBlank() &&
            recipeAckHandler != null &&
            recipeCodec.isRecipeReportAckPayload(payload)
        ) {
            recipeAckHandler(correlation, payload)
            return AckRouteOutcome.HANDLED
        }

        if (
            !correlation.isNullOrBlank() &&
            cellsContentAckHandler != null &&
            CellsContentReportAckSemantics.isContentReportAckPayload(payload)
        ) {
            cellsContentAckHandler(correlation, payload)
            return AckRouteOutcome.HANDLED
        }

        val dailyRemaining = payload["dailyRemainingMl"]
        val volumeAfter = payload["volumeAfterMl"]
        if (dailyRemaining != null || volumeAfter != null) {
            if (pourBalanceHandler != null && !isUnlimitedPlainWaterAck(payload)) {
                pourBalanceHandler(payload)
                return AckRouteOutcome.HANDLED
            }
        }

        val subscriptionLevels = payload["levels"]
        val subscriptionPayment = payload["paymentId"]
        if (subscriptionLevels != null || subscriptionPayment != null) {
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
        cellsContentErrorHandler: (suspend (String?, String, String) -> Unit)? = null,
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
        cellsContentErrorHandler?.invoke(correlation, code, message)
        if (isTechnicianErrorPayload(payload, code)) {
            technicianErrorHandler?.invoke(correlation, code, message)
            return AckRouteOutcome.HANDLED
        }
        loyaltyErrorHandler?.invoke(correlation, code, message)
        return AckRouteOutcome.ORPHAN
    }

    private suspend fun routeMatchedOutboxAck(
        entry: MachineOutboxEntryEntity,
        kind: MachineOutboxKind,
        sessionGeneration: Long,
        payload: JsonObject,
        ackKey: String,
        markAcked: suspend () -> Unit,
        pourBalanceHandler: (suspend (JsonObject) -> Unit)?,
        onUnprovenPourDedupAck: (suspend (MachineOutboxEntryEntity) -> Unit)? = null,
    ): AckRouteOutcome {
        if (entry.sessionGenerationAtSend != sessionGeneration) {
            return staleOutboxAck(ackKey, sessionGeneration)
        }
        if (kind == MachineOutboxKind.TELEMETRY_POUR_REPORT && isUnprovenPourDedupAck(payload, entry)) {
            onUnprovenPourDedupAck?.invoke(entry)
            return rejectUnprovenPourDedupAck(ackKey, payload)
        }
        val isFirstAck = entry.status != MachineOutboxStatus.ACKED.name
        markAcked()
        if (isFirstAck && kind == MachineOutboxKind.TELEMETRY_POUR_REPORT && shouldMergePourBalance(entry, payload)) {
            pourBalanceHandler?.invoke(payload)
        }
        return AckRouteOutcome.HANDLED
    }

    internal fun shouldMergePourBalance(
        outboxEntry: MachineOutboxEntryEntity,
        ackPayload: JsonObject,
    ): Boolean {
        if (isPlainWaterPourOutbox(outboxEntry)) return false
        if (isUnlimitedPlainWaterAck(ackPayload)) return false
        return true
    }

    internal fun isPlainWaterPourOutbox(entry: MachineOutboxEntryEntity): Boolean {
        if (MachineOutboxKind.fromWire(entry.kind) != MachineOutboxKind.TELEMETRY_POUR_REPORT) return false
        return runCatching {
            val obj = outboxJson.parseToJsonElement(entry.payloadJson).jsonObject
            obj["pourKind"]?.jsonPrimitive?.content == PourKind.PLAIN_WATER.wireValue
        }.getOrDefault(false)
    }

    internal fun isUnlimitedPlainWaterAck(payload: JsonObject): Boolean {
        val billingMode = payload["billingMode"]?.jsonPrimitive?.content
        if (billingMode == "UNLIMITED") return true
        val pourKind = payload["pourKind"]?.jsonPrimitive?.content
        return pourKind == PourKind.PLAIN_WATER.wireValue
    }

    /**
     * WS messageId dedup can ack `{ ok, deduplicated: true }` without proving loyalty_pours persistence.
     * Require requestUuid (payload or matched outbox idempotency key) plus a domain field before ACK.
     */
    internal fun isUnprovenPourDedupAck(
        payload: JsonObject,
        outboxEntry: MachineOutboxEntryEntity?,
    ): Boolean {
        if (payload["deduplicated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != true) {
            return false
        }
        return !hasPourReportPersistenceProof(payload, outboxEntry)
    }

    internal fun hasPourReportPersistenceProof(
        payload: JsonObject,
        outboxEntry: MachineOutboxEntryEntity?,
    ): Boolean {
        val requestUuid =
            payload["requestUuid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: outboxEntry?.idempotencyKey?.takeIf { it.isNotBlank() }
        if (requestUuid.isNullOrBlank()) return false

        val hasPourId = !payload["pourId"]?.jsonPrimitive?.content.isNullOrBlank()
        val hasIdempotent = payload["idempotent"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        val hasBalanceField =
            payload["dailyRemainingMl"] != null ||
                payload["volumeAfterMl"] != null ||
                payload["volumeMl"] != null

        return hasPourId || hasIdempotent || hasBalanceField
    }

    private fun rejectUnprovenPourDedupAck(key: String, payload: JsonObject): AckRouteOutcome {
        Timber.w(
            "TelemetryAckRouter: rejecting unproven pour dedup ack key=$key " +
                "payloadKeys=${payload.keys}; rotating transport messageId for retry",
        )
        return AckRouteOutcome.UNPROVEN_POUR_DEDUP
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

        private val outboxJson =
            Json {
                ignoreUnknownKeys = true
            }
    }
}

