package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import javax.inject.Inject

class InitMachineSubscriptionPaymentUseCase
@Inject
constructor(
    private val repository: MachineSubscriptionPaymentRepository,
) {
    suspend operator fun invoke(params: SubscriptionPaymentInitParams): Result<SubscriptionPaymentInit> =
        repository.initPayment(params)
}
