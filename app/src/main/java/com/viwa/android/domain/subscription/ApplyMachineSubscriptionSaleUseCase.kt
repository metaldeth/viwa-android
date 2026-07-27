package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import javax.inject.Inject

class ApplyMachineSubscriptionSaleUseCase
@Inject
constructor(
    private val repository: MachineSubscriptionPaymentRepository,
) {
    suspend operator fun invoke(
        params: SubscriptionSaleParams,
        paymentStatus: SubscriptionPaymentStatus,
    ): Result<Unit> {
        if (paymentStatus != SubscriptionPaymentStatus.PAID) {
            return Result.failure(
                IllegalStateException("subscribe.sale requires PAID payment, got $paymentStatus"),
            )
        }
        return repository.applySubscriptionSale(params)
    }
}
