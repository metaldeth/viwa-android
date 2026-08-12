package com.viwa.android.services.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** WS envelope: clientId=serial автомата, body=UUID клиента строкой (как контракт сервера). */
@Serializable
data class StatusSubscribeTopicRequest(
    val clientId: String,
    val type: String = "statusSubscribeTopic",
    val body: String,
)

/** Запрос тарифов подписки по orgId (machineInfo.organizationId). */
@Serializable
data class SubscriptionLevelTopicRequest(
    val type: String = "subscriptionLevelTopic",
    val clientId: String,
    val body: Int,
)

/** Тип операции в saleSubscribeTopic: покупка/продление или отмена по таймауту. */
enum class SaleSubscribeOperationType {
    SALE,
    CANCEL,
}

/** Тело saleSubscribeTopic (. */
@Serializable
data class SaleSubscribeTopicBody(
    val machineClientId: String,
 /** UUID клиента подписки в контракте telemetry-loyalty/telemetry-sale (`body.clientId`). */
    @SerialName("clientId")
    val userUuid: String,
    val machineId: Int,
    val requestUuid: String,
    val operationType: SaleSubscribeOperationType,
    val price: Double? = null,
    val monthCount: Int? = null,
    val payMethod: String? = null,
    val subscribeLevelUuid: String? = null,
)

/** Envelope для saleSubscribeTopic. */
@Serializable
data class SaleSubscribeTopicRequest(
    val type: String = "saleSubscribeTopic",
    val clientId: String,
    val body: SaleSubscribeTopicBody,
)

/** Нормализованные данные из subscribeInformationTopic для UI. */
data class SubscribeInformationState(
    val isStatusRequest: Boolean,
    val isActiveSubscribe: Boolean,
    val clientId: String,
    val subscribeDateEnd: String?,
    val volumeMl: Int,
    val maxVolumeMl: Int,
    val requestUuid: String? = null,
    val operationType: String? = null,
    /** Telemetry `lastPlainWaterType` from loyalty.status.get (FILTERED|COLD|SPARKLING). */
    val lastPlainWaterType: String? = null,
)

/** Типы оплаты для useSubscriptionSaleTopic. */
enum class UseSubscriptionPayMethod {
    CASH,
    CARD,
    FREE_MODE,
    QR_CODE,
    RF_ID,
    SBP,
    YandexBadge,
    YANDEX_BADGE,
    SUBSCRIBE,
}

/** Body для useSubscriptionSaleTopic. */
@Serializable
data class UseSubscriptionSaleBody(
    val clientId: String,
    val volume: Double,
    val machineId: Int,
    val isFree: Boolean,
    val ingredientId: Int,
    val requestUuid: String,
    val date: String,
    val payMethod: UseSubscriptionPayMethod,
    val price: Double,
)

/** Envelope для useSubscriptionSaleTopic. */
@Serializable
data class UseSubscriptionSaleTopicRequest(
    val type: String = "useSubscriptionSaleTopic",
    val clientId: String,
    val body: UseSubscriptionSaleBody,
)
