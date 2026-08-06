package com.viwa.android.ui.screens.service.devices

import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.data.payment.aqsi.UsbPaymentResult
import com.viwa.android.data.payment.aqsi.UsbPaymentStatus
import com.viwa.android.hardware.serial.SerialDeviceInfo
import com.viwa.android.hardware.serial.ViwaSerialPort
import com.viwa.android.services.payment.PillUsbLeaseResult
import com.viwa.android.services.payment.PillUsbOwner
import com.viwa.android.services.payment.PillUsbSessionOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

data class PaymentTerminalBlockState(
    val paymentStatus: UsbPaymentStatus = UsbPaymentStatus.IDLE,
    val terminalStatus: String = "",
    val exchangeLogLines: List<String> = emptyList(),
    val testResult: String? = null,
    val isTestRunning: Boolean = false,
    val usbDevices: List<SerialDeviceInfo> = emptyList(),
    val isUsbLoading: Boolean = false,
)

class PaymentTerminalBlockController(
    private val aqsiUsbPaymentManager: AqsiUsbPaymentManager,
    private val serialPort: ViwaSerialPort,
    private val pillUsbSessionOwner: PillUsbSessionOwner,
    parentScope: CoroutineScope,
) {
    private val supervisor = SupervisorJob(parentScope.coroutineContext.job)
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)

    private val testResult = MutableStateFlow<String?>(null)
    private val isTestRunning = MutableStateFlow(false)
    private val usbDevices = MutableStateFlow<List<SerialDeviceInfo>>(emptyList())
    private val isUsbLoading = MutableStateFlow(false)

    init {
        loadUsbDevices()
    }

    private val exchangeLines =
        aqsiUsbPaymentManager.exchangeLogFlow.map { lines ->
            lines.takeLast(EXCHANGE_TAIL_SIZE)
        }

    val state: StateFlow<PaymentTerminalBlockState> =
        combine(
            combine(
                aqsiUsbPaymentManager.stateFlow,
                aqsiUsbPaymentManager.terminalStatusFlow,
                exchangeLines,
            ) { status: UsbPaymentStatus, terminalStatus: String, exchangeLogLines: List<String> ->
                Triple(status, terminalStatus, exchangeLogLines)
            },
            combine(testResult, isTestRunning, combine(usbDevices, isUsbLoading) { d, l -> d to l }) {
                    result: String?,
                    running: Boolean,
                    usb: Pair<List<SerialDeviceInfo>, Boolean>,
                ->
                Triple(result, running, usb)
            },
        ) { payment, extras ->
            val (status, terminalStatus, exchangeLogLines) = payment
            val (result, running, usb) = extras
            val (devices, usbLoading) = usb
            PaymentTerminalBlockState(
                paymentStatus = status,
                terminalStatus = terminalStatus,
                exchangeLogLines = exchangeLogLines,
                testResult = result,
                isTestRunning = running,
                usbDevices = devices,
                isUsbLoading = usbLoading,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            PaymentTerminalBlockState(),
        )

    fun loadUsbDevices() {
        scope.launch {
            isUsbLoading.update { true }
            runCatching { serialPort.availableDevices() }
                .onSuccess { usbDevices.value = it }
                .onFailure { usbDevices.value = emptyList() }
            isUsbLoading.update { false }
        }
    }

    fun testPayment() {
        scope.launch {
            testResult.update { null }
            isTestRunning.update { true }
            val message =
                when {
                    pillUsbSessionOwner.activeOwner.value == PillUsbOwner.PROVISIONING ->
                        formatFailure(
                            PAYMENT_TERMINAL_BUSY,
                            "Терминал занят настройкой Pill",
                        )
                    else ->
                        when (val result = aqsiUsbPaymentManager.testPayment()) {
                            is UsbPaymentResult.Success ->
                                "Успех: ${result.transactionId}, ${result.amountKopecks} коп."
                            is UsbPaymentResult.Failure ->
                                formatFailure(mapPaymentErrorCode(result.errorCode), result.message)
                            UsbPaymentResult.Cancelled -> "Отменено"
                            UsbPaymentResult.Timeout -> "Таймаут"
                        }
                }
            testResult.update { message }
            isTestRunning.update { false }
        }
    }

    private fun mapPaymentErrorCode(errorCode: String): String =
        when (errorCode) {
            PillUsbLeaseResult.USB_SESSION_BUSY_CODE -> PAYMENT_TERMINAL_BUSY
            else -> errorCode
        }

    private fun formatFailure(code: String, message: String): String = "Ошибка ($code): $message"

    fun close() {
        supervisor.cancel()
    }

    private companion object {
        const val EXCHANGE_TAIL_SIZE = 18
        const val PAYMENT_TERMINAL_BUSY = "PAYMENT_TERMINAL_BUSY"
    }
}
