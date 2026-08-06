package com.viwa.android.ui.screens.service.devices

import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer
import com.viwa.android.hardware.controller.ControllerHardwareManager
import com.viwa.android.hardware.devices.DeviceRuntimeDiscoveryResult
import com.viwa.android.hardware.devices.ViwaControllerMockModePort
import com.viwa.android.hardware.devices.ViwaControllerPortProbe
import com.viwa.android.hardware.devices.ViwaDeviceRuntimeDiscovery
import com.viwa.android.hardware.scanner.ViwaScannerPortFacade
import com.viwa.android.hardware.scanner.ViwaScannerStartupInitializer
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.hardware.serial.SerialDeviceInfo
import com.viwa.android.hardware.serial.SerialPortAssignmentEvents
import com.viwa.android.hardware.serial.ViwaSerialPort
import com.viwa.android.services.payment.PillUsbOwner
import com.viwa.android.services.payment.PillUsbSessionOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

data class DevicesBlockState(
    val devices: List<SerialDeviceInfo> = emptyList(),
    val assignments: Map<String, PortRole> = emptyMap(),
    val scannerStatus: ScannerDeviceStatus = ScannerDeviceStatus.NotAssigned,
    val paymentStatus: PaymentDeviceStatus = PaymentDeviceStatus.NotAssigned,
    val controllerStatus: ControllerDeviceStatus = ControllerDeviceStatus.NotAssigned,
    val isControllerMockEnabled: Boolean = false,
    val showControllerMockToggle: Boolean = true,
    val controllerDiscoveryMessage: String? = null,
    val scannerDiscoveryMessage: String? = null,
    val paymentDiscoveryMessage: String? = null,
    val usesStartupDiscovery: Boolean = true,
    val scannerDiscoveryResults: List<SerialDeviceInfo> = emptyList(),
    val paymentDiscoveryResults: List<SerialDeviceInfo> = emptyList(),
    val isScannerDiscoveryRunning: Boolean = false,
    val isPaymentDiscoveryRunning: Boolean = false,
    val isControllerDiscoveryRunning: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface ScannerDeviceStatus {
    data object NotAssigned : ScannerDeviceStatus

    data class Connected(val deviceName: String) : ScannerDeviceStatus

    data class Error(
        val deviceName: String,
        val message: String,
    ) : ScannerDeviceStatus
}

sealed interface PaymentDeviceStatus {
    data object NotAssigned : PaymentDeviceStatus

    data class Ready(val deviceName: String) : PaymentDeviceStatus

    data class Busy(
        val deviceName: String,
        val message: String,
    ) : PaymentDeviceStatus

    data class Error(
        val deviceName: String,
        val message: String,
    ) : PaymentDeviceStatus
}

sealed interface ControllerDeviceStatus {
    data object NotSupported : ControllerDeviceStatus

    data object MockActive : ControllerDeviceStatus

    data object NotAssigned : ControllerDeviceStatus

    data class Assigned(val deviceName: String) : ControllerDeviceStatus

    data class Error(
        val deviceName: String,
        val message: String,
    ) : ControllerDeviceStatus
}

interface DevicesBlockActions {
    fun refreshDevices()

    fun assignRole(deviceName: String, role: PortRole)

    fun startScannerDiscovery()

    fun stopScannerDiscovery()

    fun startPaymentDiscovery()

    fun stopPaymentDiscovery()

    fun startControllerAutoDiscovery()

    fun setControllerMockEnabled(enabled: Boolean)
}

class DevicesBlockController(
    private val serialPort: ViwaSerialPort,
    private val scannerPort: ViwaScannerPortFacade,
    private val controllerPortProbe: ViwaControllerPortProbe,
    private val controllerMockModePort: ViwaControllerMockModePort,
    private val controllerHardware: ControllerHardwareManager,
    private val assignmentEvents: SerialPortAssignmentEvents,
    private val deviceRuntimeDiscovery: ViwaDeviceRuntimeDiscovery,
    private val aqsiPaymentStartupInitializer: AqsiPaymentStartupInitializer,
    private val scannerStartupInitializer: ViwaScannerStartupInitializer,
    private val pillUsbSessionOwner: PillUsbSessionOwner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : DevicesBlockActions {
    private val _state = MutableStateFlow(DevicesBlockState())
    val state: StateFlow<DevicesBlockState> = _state.asStateFlow()

    private var scannerDiscoveryJob: Job? = null
    private var paymentDiscoveryJob: Job? = null
    private var controllerDiscoveryJob: Job? = null
    private var runtimeDiscoveryJob: Job? = null

    init {
        Timber.tag(TAG).i("DevicesBlockController init")
        refreshDevices()
        scope.launch {
            assignmentEvents.changes.collect { refreshDevices() }
        }
    }

    override fun refreshDevices() {
        Timber.tag(TAG).i("refreshDevices invoked")
        scope.launch {
            Timber.tag(TAG).d("refreshDevices start")
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                runCatching { aqsiPaymentStartupInitializer.assignIfNeeded() }
                runCatching { scannerStartupInitializer.assignIfNeeded() }
                val devices = serialPort.availableDevices()
                Timber.tag(TAG).i("refreshDevices loaded %d devices", devices.size)
                val assignments = serialPort.assignments()
                val isMock = controllerMockModePort.isMockEnabled()
                val scannerStatus = resolveScannerStatus(devices, assignments)
                val paymentStatus = resolvePaymentStatus(devices, assignments)
                val controllerStatus = resolveControllerStatus(devices, isMock)
                _state.update {
                    it.copy(
                        devices = devices,
                        assignments = assignments,
                        scannerStatus = scannerStatus,
                        paymentStatus = paymentStatus,
                        controllerStatus = controllerStatus,
                        isControllerMockEnabled = isMock,
                        showControllerMockToggle = true,
                        isLoading = false,
                    )
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "refreshDevices failed")
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Не удалось загрузить USB-устройства",
                    )
                }
            }
        }
    }

    override fun assignRole(deviceName: String, role: PortRole) {
        scope.launch {
            serialPort.assign(deviceName, role)
                .onSuccess {
                    when (role) {
                        PortRole.SCANNER -> scannerPort.restart()
                        PortRole.PAYMENT -> aqsiPaymentStartupInitializer.runSetup()
                        PortRole.CONTROLLER -> {
                            val connectResult = controllerHardware.connectToPort(deviceName)
                            if (!connectResult.success) {
                                _state.update {
                                    it.copy(
                                        errorMessage =
                                            "Порт назначен, но UART не открылся (step=${connectResult.failedStep?.name ?: "UNKNOWN"})",
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                    assignmentEvents.notifyChanged()
                    refreshDevices()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(errorMessage = error.message ?: "Не удалось назначить роль порта")
                    }
                }
        }
    }

    override fun startScannerDiscovery() {
        startRuntimeDiscovery(
            isRunning = { it.copy(isScannerDiscoveryRunning = true, scannerDiscoveryMessage = null, errorMessage = null) },
            isFinished = { it.copy(isScannerDiscoveryRunning = false) },
            discover = deviceRuntimeDiscovery::discoverScanner,
            label = "Сканер",
            setMessage = { current, message -> current.copy(scannerDiscoveryMessage = message) },
        )
    }

    override fun stopScannerDiscovery() {
        scannerDiscoveryJob?.cancel()
        scannerDiscoveryJob = null
        _state.update { it.copy(isScannerDiscoveryRunning = false) }
    }

    override fun startPaymentDiscovery() {
        startRuntimeDiscovery(
            isRunning = { it.copy(isPaymentDiscoveryRunning = true, paymentDiscoveryMessage = null, errorMessage = null) },
            isFinished = { it.copy(isPaymentDiscoveryRunning = false) },
            discover = deviceRuntimeDiscovery::discoverPayment,
            label = "Платёжник",
            setMessage = { current, message -> current.copy(paymentDiscoveryMessage = message) },
        )
    }

    override fun stopPaymentDiscovery() {
        paymentDiscoveryJob?.cancel()
        paymentDiscoveryJob = null
        _state.update { it.copy(isPaymentDiscoveryRunning = false) }
    }

    override fun startControllerAutoDiscovery() {
        if (_state.value.isControllerMockEnabled) return
        startRuntimeDiscovery(
            isRunning = {
                it.copy(isControllerDiscoveryRunning = true, controllerDiscoveryMessage = null, errorMessage = null)
            },
            isFinished = { it.copy(isControllerDiscoveryRunning = false) },
            discover = deviceRuntimeDiscovery::discoverController,
            label = "Контроллер",
            setMessage = { current, message -> current.copy(controllerDiscoveryMessage = message) },
        )
    }

    override fun setControllerMockEnabled(enabled: Boolean) {
        scope.launch {
            controllerMockModePort.setMockEnabled(enabled)
            if (enabled) {
                controllerDiscoveryJob?.cancel()
                controllerDiscoveryJob = null
            }
            refreshDevices()
        }
    }

    private fun startRuntimeDiscovery(
        isRunning: (DevicesBlockState) -> DevicesBlockState,
        isFinished: (DevicesBlockState) -> DevicesBlockState,
        discover: suspend () -> DeviceRuntimeDiscoveryResult,
        label: String,
        setMessage: (DevicesBlockState, String?) -> DevicesBlockState,
    ) {
        runtimeDiscoveryJob?.cancel()
        runtimeDiscoveryJob =
            scope.launch {
                _state.update(isRunning)
                runCatching {
                    val result = discover()
                    _state.update { current ->
                        setMessage(current, formatRuntimeDiscoveryMessage(label, result))
                    }
                    refreshDevices()
                }.onFailure { error ->
                    _state.update {
                        it.copy(errorMessage = error.message ?: "Автопоиск $label не удался")
                    }
                }
                _state.update(isFinished)
            }
    }

    private fun formatRuntimeDiscoveryMessage(
        label: String,
        result: DeviceRuntimeDiscoveryResult,
    ): String =
        when (result) {
            is DeviceRuntimeDiscoveryResult.Connected ->
                result.devicePath?.let { "$label найден: $it" }
                    ?: "$label подключён (mock / без UART)"
            DeviceRuntimeDiscoveryResult.NotFound -> "$label не найден"
        }

    private suspend fun resolveScannerStatus(
        devices: List<SerialDeviceInfo>,
        @Suppress("UNUSED_PARAMETER") assignments: Map<String, PortRole>,
    ): ScannerDeviceStatus {
        val scannerDeviceName =
            serialPort.assignedDeviceName(PortRole.SCANNER)
                ?: return ScannerDeviceStatus.NotAssigned
        val device = devices.firstOrNull { it.deviceName == scannerDeviceName }
        if (device == null) {
            return ScannerDeviceStatus.Connected(scannerDeviceName)
        }
        if (device.driverType == null) {
            return ScannerDeviceStatus.Error(
                deviceName = scannerDeviceName,
                message = "Для порта сканера не найден serial-драйвер",
            )
        }
        if (scannerPort.holdsUsbDevice(scannerDeviceName)) {
            return ScannerDeviceStatus.Connected(scannerDeviceName)
        }
        return serialPort.probeOpen(scannerDeviceName).fold(
            onSuccess = { ScannerDeviceStatus.Connected(scannerDeviceName) },
            onFailure = { error ->
                ScannerDeviceStatus.Error(
                    deviceName = scannerDeviceName,
                    message = error.message ?: "Не удалось открыть порт сканера",
                )
            },
        )
    }

    private suspend fun resolvePaymentStatus(
        devices: List<SerialDeviceInfo>,
        @Suppress("UNUSED_PARAMETER") assignments: Map<String, PortRole>,
    ): PaymentDeviceStatus {
        val paymentDeviceName =
            serialPort.assignedDeviceName(PortRole.PAYMENT)
                ?: inferAqsiPaymentDeviceName(devices)
                ?: return PaymentDeviceStatus.NotAssigned
        val device = devices.firstOrNull { it.deviceName == paymentDeviceName }
        if (device == null) {
            return PaymentDeviceStatus.Ready(paymentDeviceName)
        }
        if (device.driverType == null) {
            return PaymentDeviceStatus.Error(
                deviceName = paymentDeviceName,
                message = "Для порта платёжника не найден serial-драйвер",
            )
        }
        if (AqsiPillUsbIdentifiers.isAqsiPill(device)) {
            pillUsbSessionOwner.activeOwner.value?.let { owner ->
                return PaymentDeviceStatus.Busy(
                    deviceName = paymentDeviceName,
                    message =
                        when (owner) {
                            PillUsbOwner.CUSTOMER_PAYMENT -> "Терминал занят оплатой"
                            PillUsbOwner.PROVISIONING -> "Терминал занят настройкой Pill"
                        },
                )
            }
        }
        return serialPort.probeOpen(paymentDeviceName).fold(
            onSuccess = { PaymentDeviceStatus.Ready(paymentDeviceName) },
            onFailure = { error ->
                PaymentDeviceStatus.Error(
                    deviceName = paymentDeviceName,
                    message = error.message ?: "Не удалось открыть порт платёжника",
                )
            },
        )
    }

    private suspend fun resolveControllerStatus(
        devices: List<SerialDeviceInfo>,
        isMock: Boolean,
    ): ControllerDeviceStatus {
        if (isMock) return ControllerDeviceStatus.MockActive
        val controllerDeviceName =
            serialPort.controllerDevicePath()
                ?: return ControllerDeviceStatus.NotAssigned
        val device =
            devices.firstOrNull { it.deviceName == controllerDeviceName }
                ?: return ControllerDeviceStatus.Assigned(controllerDeviceName)
        return serialPort.probeOpen(controllerDeviceName).fold(
            onSuccess = { ControllerDeviceStatus.Assigned(controllerDeviceName) },
            onFailure = { error ->
                ControllerDeviceStatus.Error(
                    deviceName = controllerDeviceName,
                    message = error.message ?: "Не удалось открыть порт контроллера",
                )
            },
        )
    }

    private fun inferAqsiPaymentDeviceName(devices: List<SerialDeviceInfo>): String? {
        val aqsiDevices = devices.filter { AqsiPillUsbIdentifiers.isAqsiPill(it) }
        return aqsiDevices.singleOrNull()?.deviceName ?: aqsiDevices.firstOrNull()?.deviceName
    }

    private companion object {
        const val TAG = "DevicesBlock"
        const val DISCOVERY_POLL_MS = 1_000L
    }
}
