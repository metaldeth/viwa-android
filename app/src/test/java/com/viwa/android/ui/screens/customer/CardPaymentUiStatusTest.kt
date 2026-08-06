package com.viwa.android.ui.screens.customer

import org.junit.Assert.assertEquals
import org.junit.Test

class CardPaymentUiStatusTest {

    @Test
    fun resolveCombined_preservesSuccessAfterConfirmed() {
        assertEquals(
            CardPaymentUiStatus.Success,
            resolveCombinedCardPaymentUiStatus(
                terminalBanner = "Приложите карту к терминалу",
                current = CardPaymentUiStatus.Success,
                paymentConfirmed = true,
            ),
        )
    }

    @Test
    fun resolveCombined_preservesSuccessBeforeConfirmed() {
        assertEquals(
            CardPaymentUiStatus.Success,
            resolveCombinedCardPaymentUiStatus(
                terminalBanner = "Приложите карту к терминалу",
                current = CardPaymentUiStatus.Success,
                paymentConfirmed = false,
            ),
        )
    }

    @Test
    fun normalize_blankTerminal_returnsAttachCard() {
        assertEquals(CardPaymentUiStatus.AttachCard, normalizeCardPaymentUiStatus(""))
    }

    @Test
    fun normalize_pinPrompt_mapsToProcessing() {
        assertEquals(CardPaymentUiStatus.Processing, normalizeCardPaymentUiStatus("Введите PIN"))
    }

    @Test
    fun normalize_bankProcessing_mapsToProcessing() {
        assertEquals(
            CardPaymentUiStatus.Processing,
            normalizeCardPaymentUiStatus("Терминал устанавливает связь с банком"),
        )
        assertEquals(
            CardPaymentUiStatus.Processing,
            normalizeCardPaymentUiStatus("Банк обрабатывает платёж"),
        )
    }

    @Test
    fun normalize_success_mapsCorrectly() {
        assertEquals(
            CardPaymentUiStatus.Success,
            normalizeCardPaymentUiStatus("Оплата прошла успешно"),
        )
    }

    @Test
    fun normalize_attachCard_mapsCorrectly() {
        assertEquals(
            CardPaymentUiStatus.AttachCard,
            normalizeCardPaymentUiStatus("Приложите карту к терминалу"),
        )
    }

    @Test
    fun normalize_unknownIntermediate_mapsToProcessing() {
        assertEquals(
            CardPaymentUiStatus.Processing,
            normalizeCardPaymentUiStatus("Получаем итоговый код оплаты"),
        )
    }

    @Test
    fun normalize_declinedPayment_mapsToFailure() {
        assertEquals(
            CardPaymentUiStatus.Failure,
            normalizeCardPaymentUiStatus("Оплата отклонена банком"),
        )
        assertEquals(
            CardPaymentUiStatus.Failure,
            normalizeCardPaymentUiStatus("Оплата завершилась неуспешно"),
        )
    }
}
