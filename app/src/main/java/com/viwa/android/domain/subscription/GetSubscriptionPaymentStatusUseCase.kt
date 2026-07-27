package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import javax.inject.Inject

class GetSubscriptionPaymentStatusUseCase
@Inject
constructor(
    private val repository: MachineSubscriptionPaymentRepository,
) {
    suspend operator fun invoke(paymentId: String): Result<SubscriptionPaymentStatusResult> =
        repository.getPaymentStatus(paymentId)
}
