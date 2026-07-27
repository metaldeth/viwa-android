package com.viwa.android.data.telemetry

import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import com.viwa.android.domain.subscription.LoyaltyPaymentException
import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatusResult
import com.viwa.android.domain.subscription.SubscriptionSaleParams
import com.viwa.android.services.telemetry.ViwaTelemetryService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MachineSubscriptionPaymentRepositoryImpl
@Inject
constructor(
    private val telemetryService: ViwaTelemetryService,
) : MachineSubscriptionPaymentRepository {
    override suspend fun initPayment(params: SubscriptionPaymentInitParams): Result<SubscriptionPaymentInit> =
        telemetryService.sendPaymentInit(params)

    override suspend fun getPaymentStatus(paymentId: String): Result<SubscriptionPaymentStatusResult> =
        telemetryService.sendPaymentStatusGet(paymentId)

    override suspend fun completeCardPayment(
        paymentId: String,
        requestUuid: String,
        externalRef: String?,
    ): Result<SubscriptionPaymentStatusResult> =
        telemetryService.sendPaymentComplete(paymentId, requestUuid, externalRef)

    override suspend fun applySubscriptionSale(params: SubscriptionSaleParams): Result<Unit> =
        telemetryService.sendSubscribeSale(params)

    override suspend fun cancelSubscription(clientId: String, requestUuid: String): Result<Unit> =
        telemetryService.sendSubscribeCancel(clientId, requestUuid)
}
