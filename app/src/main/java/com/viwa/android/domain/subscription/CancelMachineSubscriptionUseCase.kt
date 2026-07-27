package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import javax.inject.Inject

class CancelMachineSubscriptionUseCase
@Inject
constructor(
    private val repository: MachineSubscriptionPaymentRepository,
) {
    suspend operator fun invoke(clientId: String, requestUuid: String): Result<Unit> =
        repository.cancelSubscription(clientId, requestUuid)
}
