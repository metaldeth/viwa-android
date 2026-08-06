package com.viwa.android.ui.screens.customer

/**
 * Нормализованные статусы карточной оплаты для клиентского overlay (без сырых строк терминала).
 */
enum class CardPaymentUiStatus(val label: String) {
    AttachCard("Приложите карту"),
    Processing("Обрабатываем"),
    Success("Успех"),
    Failure("Не получилось"),
}

/** Статус карты в combined-overlay: Success не понижается после достижения Success. */
fun resolveCombinedCardPaymentUiStatus(
    terminalBanner: String,
    current: CardPaymentUiStatus,
    paymentConfirmed: Boolean,
): CardPaymentUiStatus {
    if (current == CardPaymentUiStatus.Success) {
        return CardPaymentUiStatus.Success
    }
    return normalizeCardPaymentUiStatus(terminalBanner)
}

/** PIN и прочие промежуточные состояния терминала → ближайший из [CardPaymentUiStatus]. */
fun normalizeCardPaymentUiStatus(terminalBanner: String): CardPaymentUiStatus {
    val raw = terminalBanner.trim()
    if (raw.isEmpty()) return CardPaymentUiStatus.AttachCard
    val t = raw.lowercase()
    return when {
        t.contains("отменя") ||
            t.contains("таймаут") ||
            t.contains("отклон") ||
            t.contains("ошиб") ||
            t.contains("неуспеш") ||
            t.contains("отказ") -> CardPaymentUiStatus.Failure
        t.contains("успех") ||
            t.contains("успешно") ||
            t.contains("оплата прошла") ||
            t.contains("подтвержден") && t.contains("оплат") -> CardPaymentUiStatus.Success
        t.contains("приложите") ||
            t.contains("ожидание карты") ||
            t.contains("ожидани") && t.contains("карт") -> CardPaymentUiStatus.AttachCard
        t.contains("банк") ||
            t.contains("связь с банком") ||
            t.contains("банком") && t.contains("обрабатыва") ||
        t.contains("pin") ||
            t.contains("пин") ||
            t.contains("обрабатыва") ||
            t.contains("получен результат") ||
            t.contains("итоговый код") ||
            t.contains("завершает операцию") ||
            t.contains("aqsi:") && !t.contains("приложите") -> CardPaymentUiStatus.Processing
        else -> CardPaymentUiStatus.Processing
    }
}
