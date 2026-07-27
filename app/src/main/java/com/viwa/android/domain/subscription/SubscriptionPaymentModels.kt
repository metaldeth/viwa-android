package com.viwa.android.domain.subscription

enum class SubscriptionPayMethod {
    SBP,
    CARD,
    ;

    fun wireValue(): String = name
}

enum class SubscriptionPaymentStatus {
    PENDING,
    PAID,
    FAILED,
    EXPIRED,
    ;

    companion object {
        fun fromWire(value: String): SubscriptionPaymentStatus? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class SubscriptionPaymentInit(
    val paymentId: String,
    val amountKopecks: Int,
    val status: SubscriptionPaymentStatus,
    val sbpQrUrl: String? = null,
    val expiresAt: String? = null,
)

data class SubscriptionPaymentInitParams(
    val clientId: String,
    val subscriptionLevelId: String,
    val payMethod: SubscriptionPayMethod,
    val requestUuid: String,
)

data class SubscriptionPaymentStatusResult(
    val paymentId: String,
    val status: SubscriptionPaymentStatus,
    val paidAt: String? = null,
)

data class SubscriptionSaleParams(
    val paymentId: String,
    val requestUuid: String,
    val clientId: String,
    val subscriptionLevelId: String,
    val payMethod: SubscriptionPayMethod,
)

class LoyaltyPaymentException(
    val code: String,
    override val message: String,
) : Exception(message)
