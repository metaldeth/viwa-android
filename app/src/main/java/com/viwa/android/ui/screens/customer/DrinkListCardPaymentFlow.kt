package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.payment.TerminalProductType
import java.util.concurrent.atomic.AtomicBoolean

/** Канал, первым подтвердивший оплату напитка в combined-сценарии. */
enum class CombinedPaymentWinner {
    Card,
    Sbp,
}

internal object DrinkListCardPaymentFlow {

    suspend fun runDrinkPaymentBeforePour(
        container: DrinkContainer,
        volume: Int,
        sbp: Boolean,
        cardPaymentOrchestrator: CardPaymentOrchestrator,
        controllerSbpNotifyService: ControllerSbpNotifyService,
        cardAlreadyPaid: Boolean = false,
    ) {
        val priceRub = container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
        if (sbp) {
            controllerSbpNotifyService.notifySbpPayment(
                TerminalProductType.Drink,
                priceRub,
                container.containerNumber,
            )
            return
        }
        if (cardAlreadyPaid) return
        when (
            val payResult =
                cardPaymentOrchestrator.pay(
                    TerminalProductType.Drink,
                    priceRub,
                    container.containerNumber,
                    sbp = false,
                )
        ) {
            CardPaymentResult.Success -> Unit
            is CardPaymentResult.Failed ->
                throw IllegalStateException(
                    payResult.reason.ifBlank { "Ошибка оплаты картой" },
                )
            CardPaymentResult.Cancelled ->
                throw IllegalStateException("Оплата отменена")
        }
    }

    /**
     * Атомарно фиксирует победителя combined-оплаты; повторные вызовы возвращают false.
     */
    fun claimCombinedPaymentWinner(
        settled: AtomicBoolean,
        winner: CombinedPaymentWinner,
    ): Boolean = settled.compareAndSet(false, true)
}
