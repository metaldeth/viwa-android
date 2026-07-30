package com.viwa.android.data.telemetry.loyalty

import com.viwa.android.services.telemetry.SubscribeInformationState
import com.viwa.android.services.telemetry.SubscriptionLevelItem
import kotlinx.serialization.Serializable

@Serializable
data class LoyaltyStatusGetPayload(
    val clientId: String,
)

@Serializable
data class LoyaltyStatusAckPayload(
    val clientId: String,
    val active: Boolean = false,
    val volumeMl: Int = 0,
    val dailyLimitMl: Int = 0,
    val dailyUsedMl: Int = 0,
    val dailyRemainingMl: Int = 0,
    val tierName: String? = null,
    val subscriptionEndsAt: String? = null,
    val limitExhausted: Boolean = false,
    val limitResetsAt: String? = null,
)

@Serializable
data class LoyaltyLevelDto(
    val id: String,
    val name: String,
    val dailyVolumeMl: Int,
    val priceKopecks: Int,
)

@Serializable
data class LoyaltyLevelsAckPayload(
    val levels: List<LoyaltyLevelDto> = emptyList(),
)

data class LoyaltyWaterUseRequest(
    val clientId: String,
    val requestUuid: String,
    val volumeMl: Int,
    val drinkId: Int? = null,
    val ingredientId: Int? = null,
    val isFree: Boolean,
    val priceKopecks: Int,
)

fun LoyaltyStatusAckPayload.toSubscribeInformationState(): SubscribeInformationState {
    // Подписка: шкала = monthly/daily pool (dailyRemainingMl).
    // Trial / без пула: шкала = кошелёк клиента volumeMl (бесплатный литр), иначе dailyRemaining=0.
    val usePoolRemaining = active && dailyLimitMl > 0
    val displayVolumeMl =
        if (usePoolRemaining) {
            dailyRemainingMl.coerceAtLeast(0)
        } else {
            volumeMl.coerceAtLeast(0)
        }
    val displayMaxMl =
        if (usePoolRemaining) {
            dailyLimitMl.coerceAtLeast(0)
        } else {
            volumeMl.coerceAtLeast(0)
        }
    return SubscribeInformationState(
        isStatusRequest = true,
        isActiveSubscribe = active && !limitExhausted,
        clientId = clientId,
        subscribeDateEnd = subscriptionEndsAt,
        volumeMl = displayVolumeMl,
        maxVolumeMl = displayMaxMl,
    )
}

fun LoyaltyLevelDto.toSubscriptionLevelItem(): SubscriptionLevelItem =
    SubscriptionLevelItem(
        uuid = id,
        price = priceKopecks / 100.0,
        name = name,
        volume = dailyVolumeMl,
    )

@Serializable
data class LoyaltyPaymentInitPayload(
    val clientId: String,
    val subscriptionLevelId: String,
    val payMethod: String,
    val requestUuid: String,
)

@Serializable
data class LoyaltyPaymentInitAckPayload(
    val paymentId: String,
    val amountKopecks: Int,
    val status: String,
    val sbpQrUrl: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class LoyaltyPaymentStatusGetPayload(
    val paymentId: String,
)

@Serializable
data class LoyaltyPaymentStatusAckPayload(
    val paymentId: String,
    val status: String,
    val paidAt: String? = null,
)

@Serializable
data class LoyaltyPaymentCompletePayload(
    val paymentId: String,
    val requestUuid: String,
    val externalRef: String? = null,
)

@Serializable
data class LoyaltySubscribeSalePayload(
    val paymentId: String,
    val requestUuid: String,
    val clientId: String,
    val subscriptionLevelId: String,
    val payMethod: String,
    val operation: String = "SALE",
)

@Serializable
data class LoyaltySubscribeCancelPayload(
    val clientId: String,
    val requestUuid: String,
    val operation: String = "CANCEL",
)
