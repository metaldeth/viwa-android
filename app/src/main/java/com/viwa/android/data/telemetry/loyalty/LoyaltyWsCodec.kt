package com.viwa.android.data.telemetry.loyalty

import com.viwa.android.services.telemetry.SubscribeInformationState
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object LoyaltyWsCodec {
    const val TYPE_STATUS_GET = "loyalty.status.get"
    const val TYPE_WATER_USE = "loyalty.water.use"
    const val TYPE_STATUS_CHANGED = "loyalty.status.changed"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    fun encodeStatusGet(clientId: String): JsonObject =
        buildJsonObject {
            put("clientId", clientId)
        }

    fun encodeWaterUse(request: LoyaltyWaterUseRequest): JsonObject =
        buildJsonObject {
            put("clientId", request.clientId)
            put("requestUuid", request.requestUuid)
            put("volumeMl", request.volumeMl)
            put("isFree", request.isFree)
            put("priceKopecks", request.priceKopecks)
            request.drinkId?.let { put("drinkId", it) }
            request.ingredientId?.let { put("ingredientId", it) }
        }

    fun decodeStatusAck(payload: JsonObject): SubscribeInformationState {
        val ack = json.decodeFromJsonElement(LoyaltyStatusAckPayload.serializer(), payload)
        return ack.toSubscribeInformationState()
    }

    fun decodeStatusAckFields(payload: JsonObject): LoyaltyStatusAckPayload =
        json.decodeFromJsonElement(LoyaltyStatusAckPayload.serializer(), payload)

    fun decodeStatusChanged(payload: JsonObject): SubscribeInformationState {
        val ack = json.decodeFromJsonElement(LoyaltyStatusAckPayload.serializer(), payload)
        return ack.toSubscribeInformationState()
    }

    /**
     * Merges pour-report ACK balance fields into [current] without zeroing unrelated fields.
     * Supports partial payloads (`dailyRemainingMl`, `volumeAfterMl`, `volumeMl` only).
     */
    fun mergePourBalanceAck(
        current: SubscribeInformationState?,
        payload: JsonObject,
    ): SubscribeInformationState? {
        val dailyRemaining = payload["dailyRemainingMl"]?.jsonPrimitive?.content?.toIntOrNull()
        val volumeAfter = payload["volumeAfterMl"]?.jsonPrimitive?.content?.toIntOrNull()
        val volumeMlField = payload["volumeMl"]?.jsonPrimitive?.content?.toIntOrNull()
        val hasBalanceField = dailyRemaining != null || volumeAfter != null || volumeMlField != null
        val hasStatusField =
            payload.containsKey("active") ||
                payload.containsKey("clientId") ||
                payload.containsKey("dailyLimitMl") ||
                payload.containsKey("subscriptionEndsAt") ||
                payload.containsKey("limitExhausted")
        if (!hasBalanceField && !hasStatusField) {
            return null
        }
        if (current == null) {
            return runCatching { decodeStatusAck(payload) }.getOrNull()
        }
        val dailyLimitField = payload["dailyLimitMl"]?.jsonPrimitive?.content?.toIntOrNull()
        val active = payload["active"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        val limitExhausted = payload["limitExhausted"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        val dailyLimit =
            if (payload.containsKey("dailyLimitMl")) {
                dailyLimitField?.coerceAtLeast(0) ?: current.maxVolumeMl
            } else {
                current.maxVolumeMl
            }
        val usePool =
            if (payload.containsKey("dailyLimitMl") || payload.containsKey("active")) {
                (active ?: current.isActiveSubscribe) && dailyLimit > 0
            } else {
                current.maxVolumeMl > 0 && current.isActiveSubscribe
            }
        val newVolume =
            when {
                usePool && dailyRemaining != null -> dailyRemaining.coerceAtLeast(0)
                volumeAfter != null -> volumeAfter.coerceAtLeast(0)
                volumeMlField != null -> volumeMlField.coerceAtLeast(0)
                else -> current.volumeMl
            }
        return current.copy(
            isStatusRequest = true,
            clientId =
                if (payload.containsKey("clientId")) {
                    payload["clientId"]?.jsonPrimitive?.contentOrNull ?: current.clientId
                } else {
                    current.clientId
                },
            subscribeDateEnd =
                if (payload.containsKey("subscriptionEndsAt")) {
                    payload["subscriptionEndsAt"]?.jsonPrimitive?.contentOrNull ?: current.subscribeDateEnd
                } else {
                    current.subscribeDateEnd
                },
            volumeMl = newVolume,
            maxVolumeMl = dailyLimit,
            isActiveSubscribe =
                when {
                    limitExhausted == true -> false
                    active != null -> active && limitExhausted != true
                    else -> current.isActiveSubscribe
                },
        )
    }

    /** Validates UUID from `CLIENT_{uuid}` scan; rejects malformed ids. */
    fun parseClientIdFromScan(rawLine: String): Result<String> {
        val trimmed = rawLine.trim()
        if (!trimmed.startsWith(CLIENT_PREFIX, ignoreCase = false)) {
            return Result.failure(IllegalArgumentException("Not a loyalty card scan"))
        }
        val clientId = trimmed.removePrefix(CLIENT_PREFIX).trim()
        if (clientId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty client id after CLIENT_ prefix"))
        }
        return runCatching { UUID.fromString(clientId) }.map { clientId }
    }

    fun encodeStatusGetEnvelopeJson(clientId: String, messageId: String, sentAt: String): String {
        val payload = encodeStatusGet(clientId)
        return json.encodeToString(
            buildJsonObject {
                put("type", TYPE_STATUS_GET)
                put("messageId", messageId)
                put("sentAt", sentAt)
                put("payload", payload)
            },
        )
    }

    private const val CLIENT_PREFIX = "CLIENT_"
}
