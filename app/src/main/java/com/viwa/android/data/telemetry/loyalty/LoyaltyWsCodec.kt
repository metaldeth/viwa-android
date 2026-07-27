package com.viwa.android.data.telemetry.loyalty

import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatus
import com.viwa.android.domain.subscription.SubscriptionPaymentStatusResult
import com.viwa.android.domain.subscription.SubscriptionPayMethod
import com.viwa.android.domain.subscription.SubscriptionSaleParams
import com.viwa.android.services.telemetry.SubscribeInformationState
import com.viwa.android.services.telemetry.SubscriptionLevelItem
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object LoyaltyWsCodec {
    const val TYPE_STATUS_GET = "loyalty.status.get"
    const val TYPE_LEVELS_LIST = "loyalty.levels.list"
    const val TYPE_WATER_USE = "loyalty.water.use"
    const val TYPE_STATUS_CHANGED = "loyalty.status.changed"
    const val TYPE_PAYMENT_INIT = "loyalty.payment.init"
    const val TYPE_PAYMENT_STATUS_GET = "loyalty.payment.status.get"
    const val TYPE_PAYMENT_COMPLETE = "loyalty.payment.complete"
    const val TYPE_SUBSCRIBE_SALE = "loyalty.subscribe.sale"
    const val TYPE_SUBSCRIBE_CANCEL = "loyalty.subscribe.cancel"

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

    fun encodeLevelsList(): JsonObject = buildJsonObject {}

    fun encodePaymentInit(params: SubscriptionPaymentInitParams): JsonObject =
        buildJsonObject {
            put("clientId", params.clientId)
            put("subscriptionLevelId", params.subscriptionLevelId)
            put("payMethod", params.payMethod.wireValue())
            put("requestUuid", params.requestUuid)
        }

    fun encodePaymentStatusGet(paymentId: String): JsonObject =
        buildJsonObject {
            put("paymentId", paymentId)
        }

    fun encodePaymentComplete(
        paymentId: String,
        requestUuid: String,
        externalRef: String?,
    ): JsonObject =
        buildJsonObject {
            put("paymentId", paymentId)
            put("requestUuid", requestUuid)
            externalRef?.let { put("externalRef", it) }
        }

    fun encodeSubscribeSale(params: SubscriptionSaleParams): JsonObject =
        buildJsonObject {
            put("paymentId", params.paymentId)
            put("requestUuid", params.requestUuid)
            put("clientId", params.clientId)
            put("subscriptionLevelId", params.subscriptionLevelId)
            put("payMethod", params.payMethod.wireValue())
            put("operation", "SALE")
        }

    fun encodeSubscribeCancel(clientId: String, requestUuid: String): JsonObject =
        buildJsonObject {
            put("clientId", clientId)
            put("requestUuid", requestUuid)
            put("operation", "CANCEL")
        }

    fun decodePaymentInitAck(payload: JsonObject): SubscriptionPaymentInit {
        val ack = json.decodeFromJsonElement(LoyaltyPaymentInitAckPayload.serializer(), payload)
        val status =
            SubscriptionPaymentStatus.fromWire(ack.status)
                ?: error("Unknown payment status: ${ack.status}")
        return SubscriptionPaymentInit(
            paymentId = ack.paymentId,
            amountKopecks = ack.amountKopecks,
            status = status,
            sbpQrUrl = ack.sbpQrUrl,
            expiresAt = ack.expiresAt,
        )
    }

    fun decodePaymentStatusAck(payload: JsonObject): SubscriptionPaymentStatusResult {
        val ack = json.decodeFromJsonElement(LoyaltyPaymentStatusAckPayload.serializer(), payload)
        val status =
            SubscriptionPaymentStatus.fromWire(ack.status)
                ?: error("Unknown payment status: ${ack.status}")
        return SubscriptionPaymentStatusResult(
            paymentId = ack.paymentId,
            status = status,
            paidAt = ack.paidAt,
        )
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

    fun decodeLevelsAck(payload: JsonObject): List<SubscriptionLevelItem> {
        val ack = json.decodeFromJsonElement(LoyaltyLevelsAckPayload.serializer(), payload)
        return ack.levels.map { it.toSubscriptionLevelItem() }
    }

    fun decodeStatusChanged(payload: JsonObject): SubscribeInformationState {
        val ack = json.decodeFromJsonElement(LoyaltyStatusAckPayload.serializer(), payload)
        return ack.toSubscribeInformationState()
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
