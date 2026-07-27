package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import javax.inject.Inject

class CompleteMachineSubscriptionPaymentUseCase
@Inject
constructor(
    private val repository: MachineSubscriptionPaymentRepository,
) {
    suspend operator fun invoke(
        paymentId: String,
        requestUuid: String,
        externalRef: String? = null,
    ): Result<SubscriptionPaymentStatusResult> =
        repository.completeCardPayment(paymentId, requestUuid, externalRef)
}
