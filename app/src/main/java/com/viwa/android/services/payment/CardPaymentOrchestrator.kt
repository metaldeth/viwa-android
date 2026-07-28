package com.viwa.android.services.payment

import com.viwa.android.domain.model.AqsiPaymentResult
import com.viwa.android.domain.model.CardPaymentMockMode
import com.viwa.android.domain.model.CardPaymentMockOutcome
import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.repository.AqsiRepository
import com.viwa.android.domain.repository.CardPaymentMockModeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Единая точка оплаты **картой** (не СБП): USB Arcus2 через [AqsiRepository].
 */
class CardPaymentOrchestrator(
    private val aqsiRepository: AqsiRepository,
    private val cardPaymentMockModeRepository: CardPaymentMockModeRepository,
    private val paymentEventLogger: CardPaymentEventLogger,
) {
    @Volatile
    private var paymentInProgress = false

    suspend fun pay(
        type: TerminalProductType,
        price: Int,
        productNumber: Int,
        sbp: Boolean,
    ): CardPaymentResult {
        check(!sbp) { "CardPaymentOrchestrator handles card payments only; use ControllerSbpNotifyService for SBP" }
        val mockMode = cardPaymentMockModeRepository.getMode()
        paymentInProgress = true
        Timber.tag(TAG).i(
            "pay start mock=%s type=%s price=%d product=%d",
            mockMode,
            type.name,
            price,
            productNumber,
        )
        return try {
            mockPaymentIfEnabled(mockMode, price)?.let { return it }
            payAqsi(price)
        } catch (e: CancellationException) {
            Timber.tag(TAG).i("pay cancelled")
            throw e
        } finally {
            paymentInProgress = false
            Timber.tag(TAG).i("pay finished")
        }
    }

    fun isPaymentInProgress(): Boolean = paymentInProgress

    suspend fun cancelActivePayment() {
        if (!paymentInProgress) {
            Timber.tag(TAG).d("cancelActivePayment: no active payment")
            return
        }
        Timber.tag(TAG).i("cancelActivePayment AQSI")
        paymentEventLogger.info(
            "aQsi",
            "Отмена активной операции",
            lane = CardPaymentLogLane.System,
        )
        aqsiRepository.cancelPayment().onFailure {
            paymentEventLogger.error(
                "aQsi",
                "Ошибка отмены",
                it.message ?: it.javaClass.simpleName,
                lane = CardPaymentLogLane.FromTerminal,
            )
            Timber.tag(TAG).w(it, "cancelActivePayment: AQSI cancel failed")
        }
    }

    private suspend fun payAqsi(priceRub: Int): CardPaymentResult {
        val kopecks = priceRub.coerceAtLeast(0) * 100
        paymentEventLogger.info(
            "aQsi",
            "Запуск оплаты",
            "$kopecks коп.",
            lane = CardPaymentLogLane.ToTerminal,
        )
        return try {
            aqsiRepository.initiatePayment(kopecks).fold(
                onSuccess = { mapAqsiToCard(it) },
                onFailure = { e ->
                    paymentEventLogger.error(
                        "aQsi",
                        "Ошибка оплаты",
                        safeThrowableMessage(e),
                        lane = CardPaymentLogLane.FromTerminal,
                    )
                    Timber.tag(TAG).w(e, "AQSI initiatePayment failure")
                    CardPaymentResult.Failed(safeThrowableMessage(e))
                },
            )
        } catch (e: CancellationException) {
            runCatching { aqsiRepository.cancelPayment() }.onFailure { ex ->
                Timber.tag(TAG).w(ex, "AQSI cleanup after cancellation")
            }
            throw e
        }
    }

    private fun mapAqsiToCard(r: AqsiPaymentResult): CardPaymentResult =
        when (r) {
            AqsiPaymentResult.Approved -> {
                paymentEventLogger.info(
                    "aQsi",
                    "Оплата подтверждена",
                    lane = CardPaymentLogLane.FromTerminal,
                )
                CardPaymentResult.Success
            }
            is AqsiPaymentResult.Declined -> {
                paymentEventLogger.info(
                    "aQsi",
                    "Оплата отклонена",
                    r.publicCode,
                    lane = CardPaymentLogLane.FromTerminal,
                )
                CardPaymentResult.Failed(
                    if (r.publicCode.isBlank()) "declined" else "declined:${r.publicCode}",
                )
            }
            is AqsiPaymentResult.Error -> {
                paymentEventLogger.error(
                    "aQsi",
                    "Ошибка оплаты",
                    r.safeMessage,
                    lane = CardPaymentLogLane.FromTerminal,
                )
                CardPaymentResult.Failed(r.safeMessage.take(256))
            }
            AqsiPaymentResult.Cancelled -> {
                paymentEventLogger.info(
                    "aQsi",
                    "Оплата отменена",
                    lane = CardPaymentLogLane.FromTerminal,
                )
                CardPaymentResult.Cancelled
            }
        }

    private suspend fun mockPaymentIfEnabled(
        mockMode: CardPaymentMockMode,
        priceRub: Int,
    ): CardPaymentResult? {
        if (mockMode !== CardPaymentMockMode.Aqsi) return null
        val outcome = cardPaymentMockModeRepository.getOutcome()
        val provider = "aQsi mock"
        paymentEventLogger.info(
            provider,
            "Mock-оплата запущена",
            "$priceRub руб., сценарий ${mockOutcomeLabel(outcome)}",
            lane = CardPaymentLogLane.Mock,
        )
        delay(300)
        return when (outcome) {
            CardPaymentMockOutcome.Approved -> {
                paymentEventLogger.info(
                    provider,
                    "Mock-оплата подтверждена",
                    "Физический платёжник не используется",
                    lane = CardPaymentLogLane.Mock,
                )
                CardPaymentResult.Success
            }
            CardPaymentMockOutcome.Declined -> {
                paymentEventLogger.info(provider, "Mock-оплата отклонена", "Тестовый отказ", lane = CardPaymentLogLane.Mock)
                CardPaymentResult.Failed("mock_declined")
            }
            CardPaymentMockOutcome.Cancelled -> {
                paymentEventLogger.info(provider, "Mock-оплата отменена", "Тестовая отмена", lane = CardPaymentLogLane.Mock)
                CardPaymentResult.Cancelled
            }
            CardPaymentMockOutcome.Timeout -> {
                paymentEventLogger.error(provider, "Mock-таймаут оплаты", "Тестовый таймаут", lane = CardPaymentLogLane.Mock)
                CardPaymentResult.Failed("mock_timeout")
            }
        }
    }

    private fun mockOutcomeLabel(outcome: CardPaymentMockOutcome): String =
        when (outcome) {
            CardPaymentMockOutcome.Approved -> "успех"
            CardPaymentMockOutcome.Declined -> "отказ"
            CardPaymentMockOutcome.Cancelled -> "отмена"
            CardPaymentMockOutcome.Timeout -> "таймаут"
        }

    private fun safeThrowableMessage(t: Throwable?): String =
        t?.message?.take(256)?.ifBlank { null } ?: t?.javaClass?.simpleName ?: "error"

    private companion object {
        const val TAG = "CardPayment"
    }
}
