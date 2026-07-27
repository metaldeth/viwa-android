package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.subscription.GetSubscriptionPaymentStatusUseCase
import com.viwa.android.domain.subscription.SubscriptionPaymentStatus
import javax.inject.Inject

class CheckSBPStatusUseCase
@Inject
constructor(
    private val repo: SBPRepository,
    private val getSubscriptionPaymentStatus: GetSubscriptionPaymentStatusUseCase,
) {
    /** Drink purchase — Paymaster polling. */
    suspend operator fun invoke(orderId: String) = repo.getSBPLinkStatus(orderId)

    /** Tier purchase — server `loyalty.payment.status.get`. */
    suspend fun forSubscriptionPayment(paymentId: String): Result<SBPStatus> =
        getSubscriptionPaymentStatus(paymentId).map { result ->
            when (result.status) {
                SubscriptionPaymentStatus.PAID -> SBPStatus.Success
                SubscriptionPaymentStatus.PENDING -> SBPStatus.Pending
                SubscriptionPaymentStatus.FAILED ->
                    SBPStatus.Failed(reason = "FAILED")
                SubscriptionPaymentStatus.EXPIRED ->
                    SBPStatus.Failed(reason = "EXPIRED")
            }
        }
}
