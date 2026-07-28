package com.viwa.android.hardware.scanner

import com.viwa.android.data.network.maskTechnicianKeyPlaintext
import com.viwa.android.data.network.redactNetworkPayload
import com.viwa.android.domain.model.BarcodeEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannerTrafficEntry(
    val id: Int,
    val timestampMs: Long,
 /** Краткая классификация для свёрнутой строки карточки. */
    val classificationLabel: String,
 /** Сырая строка со сканера (до классификации), секреты замаскированы. */
    val rawLine: String,
)

@Singleton
class ViwaScannerTrafficLogger
@Inject
constructor() {
    private val maxEntries = 50
    private val counter = AtomicInteger(0)

    private val _entries = MutableStateFlow<List<ScannerTrafficEntry>>(emptyList())
    val entries: StateFlow<List<ScannerTrafficEntry>> = _entries.asStateFlow()

    fun log(rawLine: String, event: BarcodeEvent) {
        val label = classificationLabel(event)
        val line =
            ScannerTrafficEntry(
                id = counter.getAndIncrement(),
                timestampMs = System.currentTimeMillis(),
                classificationLabel = label,
                rawLine = redactScannerRawLine(rawLine, event),
            )
        synchronized(this) {
            val next = _entries.value + line
            _entries.value = if (next.size > maxEntries) next.takeLast(maxEntries) else next
        }
    }

    fun clear() {
        synchronized(this) { _entries.value = emptyList() }
    }
}

private val regKeyPlainPattern =
    Regex("""REG[-:][0-9A-HJKMNP-TV-Z]{12}""", RegexOption.IGNORE_CASE)

internal fun redactScannerRawLine(rawLine: String, event: BarcodeEvent): String {
    val withReg =
        when (event) {
            is BarcodeEvent.RegistrationKey,
            is BarcodeEvent.TelemetryRegistrationQr,
            -> regKeyPlainPattern.replace(rawLine, "REG-************")
            else -> rawLine
        }
    return redactNetworkPayload(maskTechnicianKeyPlaintext(withReg))
}

internal fun classificationLabel(event: BarcodeEvent): String =
    when (event) {
        is BarcodeEvent.ProductBarcode -> "Товар (штрихкод)"
        is BarcodeEvent.EmployeeKey -> "Ключ сотрудника"
        is BarcodeEvent.RegistrationKey -> "REG-ключ телеметрии"
        is BarcodeEvent.TelemetryRegistrationQr -> "QR регистрации телеметрии"
        is BarcodeEvent.ClientLoyaltyCard -> "Карта клиента (подписка)"
        is BarcodeEvent.UnknownBarcode -> "Неизвестный формат"
    }
