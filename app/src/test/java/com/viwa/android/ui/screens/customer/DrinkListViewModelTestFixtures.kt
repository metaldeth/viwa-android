package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.subscription.ApplyMachineSubscriptionSaleUseCase
import com.viwa.android.domain.subscription.CancelMachineSubscriptionUseCase
import com.viwa.android.domain.subscription.CompleteMachineSubscriptionPaymentUseCase
import com.viwa.android.domain.subscription.InitMachineSubscriptionPaymentUseCase
import io.mockk.mockk

internal data class SubscriptionPaymentUseCaseMocks(
    val init: InitMachineSubscriptionPaymentUseCase,
    val complete: CompleteMachineSubscriptionPaymentUseCase,
    val apply: ApplyMachineSubscriptionSaleUseCase,
    val cancel: CancelMachineSubscriptionUseCase,
)

internal fun relaxedSubscriptionPaymentUseCases(): SubscriptionPaymentUseCaseMocks =
    SubscriptionPaymentUseCaseMocks(
        init = mockk(relaxed = true),
        complete = mockk(relaxed = true),
        apply = mockk(relaxed = true),
        cancel = mockk(relaxed = true),
    )
