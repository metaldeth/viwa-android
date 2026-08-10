package com.viwa.android.data.telemetry.loyalty

import com.viwa.android.services.telemetry.SubscribeInformationState
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
