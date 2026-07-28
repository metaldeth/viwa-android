package com.viwa.android.domain.ota

import com.viwa.android.di.AppIoScope
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PreparingManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Mirrors preparing/payment state into [OtaOperationStateRegistry] without Coordinator → PreparingManager edge. */
@Singleton
class OtaOperationStateObserver
@Inject
constructor(
    preparingManager: PreparingManager,
    private val cardPaymentOrchestrator: CardPaymentOrchestrator,
    private val registry: OtaOperationStateRegistry,
    @AppIoScope appScope: CoroutineScope,
) {
    init {
        appScope.launch {
            preparingManager.customerPhase.collect { phase ->
                    registry.pourInProgress = phase !is CustomerPreparingPhase.Idle
                }
        }
    }

    fun refreshPaymentFlag() {
        registry.paymentInProgress = cardPaymentOrchestrator.isPaymentInProgress()
    }
}
