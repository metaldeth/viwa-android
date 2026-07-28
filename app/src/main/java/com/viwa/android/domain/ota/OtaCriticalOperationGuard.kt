package com.viwa.android.domain.ota

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaCriticalOperationGuard
@Inject
constructor(
    private val operationState: OtaOperationStateRegistry,
    private val paymentObserver: OtaOperationStateObserver,
) {
    fun isCriticalOperationActive(): Boolean {
        paymentObserver.refreshPaymentFlag()
        return operationState.isCriticalOperationActive()
    }
}
