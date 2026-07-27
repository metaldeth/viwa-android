package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.subscription.InitMachineSubscriptionPaymentUseCase
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPayMethod
import javax.inject.Inject

class GetSBPLinkUseCase
@Inject
constructor(
    private val repo: SBPRepository,
    private val initSubscriptionPayment: InitMachineSubscriptionPaymentUseCase,
) {
    /** Drink purchase — legacy Paymaster QR. */
    suspend operator fun invoke(amountKopecks: Int) = repo.getSBPLink(amountKopecks)

    /** Tier purchase — server `loyalty.payment.init` (no local billing URL). */
    suspend fun forSubscription(params: SubscriptionPaymentInitParams): Result<SBPLink> =
        initSubscriptionPayment(params).map { init ->
            SBPLink(
                orderId = init.paymentId,
                url = init.sbpQrUrl.orEmpty(),
                qrData = init.sbpQrUrl.orEmpty(),
            )
        }

    suspend fun forSubscription(
        clientId: String,
        subscriptionLevelId: String,
        requestUuid: String,
    ): Result<SBPLink> =
        forSubscription(
            SubscriptionPaymentInitParams(
                clientId = clientId,
                subscriptionLevelId = subscriptionLevelId,
                payMethod = SubscriptionPayMethod.SBP,
                requestUuid = requestUuid,
            ),
        )
}
