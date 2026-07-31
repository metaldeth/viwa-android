package com.viwa.android.ui.screens.customer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.PaymentMethod
import com.viwa.android.domain.model.ReceiptItem
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.services.telemetry.ViwaTelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** UI чека после готовки (платёж + конфиг Нанокассы для чека) или галочка. */
sealed class ReceiptAfterReadyState {
    data object Idle : ReceiptAfterReadyState()

    data object LoadingReceipt : ReceiptAfterReadyState()

 /** Бесплатный налив или нет данных Нанокассы для чека. */
    data object SuccessCheckmark : ReceiptAfterReadyState()

    data class ReceiptQr(val checkPageUrl: String) : ReceiptAfterReadyState()

    data class ReceiptError(val message: String) : ReceiptAfterReadyState()
}

@HiltViewModel
class PreparingViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    val preparingManager: PreparingManager,
    private val nanoKassaRepository: NanoKassaRepository,
    private val configRepository: ConfigRepository,
    private val telemetryService: ViwaTelemetryService,
) : ViewModel() {
    val customerPhase: StateFlow<CustomerPreparingPhase> = preparingManager.customerPhase

    private val _receiptAfterReady = MutableStateFlow<ReceiptAfterReadyState>(ReceiptAfterReadyState.Idle)
    val receiptAfterReady: StateFlow<ReceiptAfterReadyState> = _receiptAfterReady.asStateFlow()

    private var drinkReadyHandled: Boolean = false

    init {
        viewModelScope.launch {
            customerPhase.collect { phase ->
                if (phase is CustomerPreparingPhase.DrinkReady) {
                    onFirstDrinkReady()
                }
            }
        }
    }

    private suspend fun onFirstDrinkReady() {
        if (drinkReadyHandled) return
        drinkReadyHandled = true

        val payMethodKey = savedStateHandle.get<String>("payMethod") ?: "none"
        val priceRub = savedStateHandle.get<Int>("priceRub") ?: 0
        val productNameArg = savedStateHandle.get<String>("productName").orEmpty()
        val needsPaidReceipt =
            payMethodKey.lowercase() !in NO_RECEIPT_PAY_METHODS && priceRub > 0

        Timber.d(
            "Preparing receipt: payMethod=%s priceRub=%d productLen=%d needsPaid=%s",
            payMethodKey,
            priceRub,
            productNameArg.length,
            needsPaidReceipt,
        )

        if (!needsPaidReceipt) {
            _receiptAfterReady.value = ReceiptAfterReadyState.SuccessCheckmark
            return
        }

 // Не требуем lastIntegrationVerifyOk: иначе после оплаты СБП всегда галочка, пока не прошла отдельная verify.
        if (!nanoKassaRepository.hasNanoFiscalConfig()) {
            Timber.d("NanoKassa: нет полной конфигурации для чека — показываем галочку")
            _receiptAfterReady.value = ReceiptAfterReadyState.SuccessCheckmark
            return
        }

        _receiptAfterReady.value = ReceiptAfterReadyState.LoadingReceipt
        fetchFiscalReceipt(payMethodKey, priceRub, productNameArg)
    }

    private suspend fun fetchFiscalReceipt(
        payMethodKey: String,
        priceRub: Int,
        productNameArg: String,
    ) {
        val pm =
            when (payMethodKey) {
                "sbp" -> PaymentMethod.SBP
                else -> PaymentMethod.CARD
            }
        val kopecks = priceRub * 100
        val label = productNameArg.ifBlank { "Напиток" }
        val item = ReceiptItem(name = label, price = kopecks, quantity = 1)
        val result =
            nanoKassaRepository.sendFiscalReceipt(
                amountKopecks = kopecks,
                items = listOf(item),
                paymentMethod = pm,
                isTest = false,
            )
        result.fold(
            onSuccess = { fr ->
                val url = fr.checkPageUrl
                if (!url.isNullOrBlank()) {
                    _receiptAfterReady.value = ReceiptAfterReadyState.ReceiptQr(url)
                } else {
                    _receiptAfterReady.value =
                        ReceiptAfterReadyState.ReceiptError("Нет ссылки на чек")
                }
            },
            onFailure = { e ->
                Timber.e(e, "sendFiscalReceipt")
                _receiptAfterReady.value =
                    ReceiptAfterReadyState.ReceiptError(e.message ?: "Ошибка Нанокассы")
            },
        )
    }

    fun retryFiscalReceipt() {
        viewModelScope.launch {
            val payMethodKey = savedStateHandle.get<String>("payMethod") ?: "none"
            val priceRub = savedStateHandle.get<Int>("priceRub") ?: 0
            val productNameArg = savedStateHandle.get<String>("productName").orEmpty()
            _receiptAfterReady.value = ReceiptAfterReadyState.LoadingReceipt
            fetchFiscalReceipt(payMethodKey, priceRub, productNameArg)
        }
    }

    fun resetSession() {
        if (savedStateHandle.get<String>("payMethod") == "subscribe") {
            telemetryService.clearSubscribeUiState()
        }
        drinkReadyHandled = false
        _receiptAfterReady.value = ReceiptAfterReadyState.Idle
        preparingManager.resetSession()
    }

 /**
 * Задержка до авто-выхода с экрана готовки (пока ждём SUCCESS), в мс. `0` — таймер выключен.
 * Ключ конфига отсутствует — **5** минут.
 */
    suspend fun getPreparingAutoExitDelayMs(): Long =
        withContext(Dispatchers.IO) {
            val raw = configRepository.get(JsonStoreKeys.PREPARING_AUTO_EXIT_MINUTES)
            val minutes =
                when (raw) {
                    null -> DEFAULT_PREPARING_AUTO_EXIT_MINUTES
                    else ->
                        raw.toIntOrNull()?.coerceIn(0, MAX_PREPARING_AUTO_EXIT_MINUTES)
                            ?: DEFAULT_PREPARING_AUTO_EXIT_MINUTES
                }
            if (minutes <= 0) 0L else minutes * 60_000L
        }

    companion object {
        private val NO_RECEIPT_PAY_METHODS = setOf("none", "subscribe", "free")

        private const val DEFAULT_PREPARING_AUTO_EXIT_MINUTES = 5
        private const val MAX_PREPARING_AUTO_EXIT_MINUTES = 240
    }
}
