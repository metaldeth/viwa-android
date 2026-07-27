package com.viwa.android.domain.repository

import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatusResult
import com.viwa.android.domain.subscription.SubscriptionSaleParams

interface MachineSubscriptionPaymentRepository {
    suspend fun initPayment(params: SubscriptionPaymentInitParams): Result<SubscriptionPaymentInit>

    suspend fun getPaymentStatus(paymentId: String): Result<SubscriptionPaymentStatusResult>

    suspend fun completeCardPayment(
        paymentId: String,
        requestUuid: String,
        externalRef: String?,
    ): Result<SubscriptionPaymentStatusResult>

    suspend fun applySubscriptionSale(params: SubscriptionSaleParams): Result<Unit>

    suspend fun cancelSubscription(clientId: String, requestUuid: String): Result<Unit>
}
