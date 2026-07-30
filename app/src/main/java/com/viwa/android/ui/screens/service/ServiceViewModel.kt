package com.viwa.android.ui.screens.service

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.repository.UpdateRepository
import com.viwa.android.hardware.FlowStripRgbCoordinator
import com.viwa.android.hardware.NativeSerialPortDetector
import com.viwa.android.hardware.controller.ConnectToPortResult
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.ControllerHardwareManager
import com.viwa.android.hardware.controller.ViwaControllerTrafficLogger
import com.viwa.android.hardware.controller.ViwaWaterCounterService
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.hardware.controller.decodeFlowTemperatureByte
import com.viwa.android.hardware.controller.RequestCommand
import com.viwa.android.hardware.controller.ResponseCommand
import com.viwa.android.services.drink.ChooseDrinkBodyBuilder
import com.viwa.android.services.drink.ViwaDrinkPreparingService
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryUrlResolver
import com.viwa.android.data.remote.telemetry.mvp.TelemetryCellsSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.CellVolumeUpdateWire
import com.viwa.android.data.remote.telemetry.mvp.RegistrationKeyException
import com.viwa.android.data.remote.telemetry.mvp.RebindNotAllowedException
import com.viwa.android.data.remote.telemetry.mvp.SerialAlreadyBoundException
import com.viwa.android.data.remote.telemetry.mvp.SerialNumberUtils
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.hardware.scanner.ViwaScannerTrafficLogger
import com.viwa.android.hardware.scanner.ScannerManager
import com.viwa.android.hardware.rfid.ViwaRfidProbeManager
import com.viwa.android.hardware.rfid.ViwaRfidTrafficLogger
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.hardware.serial.SerialPortManager
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.payment.TerminalProductType
import com.viwa.android.services.calibration.WaterCalibrationService
import com.viwa.android.services.calibration.WaterPourResult
import com.viwa.android.services.calibration.WaterCalibrationWriteResult
import com.viwa.android.services.calibration.SyrupCalibrationInventory
import com.viwa.android.services.calibration.SyrupCalibrationService
import com.viwa.android.services.telemetry.TelemetryRegistrationScannerCoordinator
import com.viwa.android.services.telemetry.TelemetryRegistrationScanApplier
import com.viwa.android.services.telemetry.TelemetryRegistrationScanUiEvent
import com.viwa.android.services.telemetry.TelemetryRegistrationUiPolicy
import com.viwa.android.services.telemetry.ViwaTelemetryService
import com.viwa.android.domain.model.customer.PrimaryButtonPulseStyle
import com.viwa.android.domain.model.WaterCalibrationData
import com.viwa.android.domain.model.AppUpdate
import com.viwa.android.domain.model.CellVolumeUpdate
import com.viwa.android.domain.model.ContainerCalibrationInfo
import com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapter
import com.viwa.android.domain.model.MvpInventoryContentUpdate
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.model.mapMvpInventoryFromSnapshot
import com.viwa.android.domain.model.MaxSettings
import com.viwa.android.domain.model.NanoKassaSettings
import com.viwa.android.domain.model.UpdateProgress
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.repository.MaxRepository
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.ui.screens.customer.ViwaCustomerUiTokens
import com.viwa.android.ui.theme.ThemeRepository
import com.viwa.android.services.preparing.PreparingTimeHistoryStore
import com.viwa.android.services.preparing.PreparingTimeRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private fun argbToHex8Store(argb: Int): String =
    argb.toUInt().toString(16).padStart(8, '0').uppercase()

data class UsbSerialDriverInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val driverType: String,
)

/** Состояние строки WS — отдельный поток, чтобы не дёргать весь [ServiceUiState] и фокус полей. */
data class TelemetryConnectionUiState(
    val label: String = "WS: не инициализировано",
    val connected: Boolean = false,
    val error: String? = null,
)

data class PreparingStatsDrinkOption(
    val tasteId: Int,
    val title: String,
    val recipeDrinkVolumeMl: Int,
    val recipeWaterMl: Double,
)

data class PreparingStatsHistoryRow(
    val timestampEpochMs: Long,
    val volumeMl: Int,
    val expectedTimeSec: Int,
    val actualTimeSec: Double,
    val deltaSec: Double,
    val deltaPercent: Double,
)

data class ServiceUiState(
    val useMockController: Boolean = false,
 /** Как freeMode в wiva: при false — оплата через терминал (0x48 + PAX). */
    val devFreeMode: Boolean = true,
    val currentVersion: String = "",
    val currentVersionCode: Int = 0,
    val updateHost: String = "",
    val availableUpdate: AppUpdate? = null,
    val otaPhase: String? = null,
    val otaChannel: String? = null,
    val otaServerFeatureEnabled: Boolean? = null,
    val otaLegacyFallbackEnabled: Boolean = false,
    val otaMandatoryEnforcementEnabled: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isInstalling: Boolean = false,
    val updateCheckError: String? = null,
    val isUpToDate: Boolean = false,
 /** Видимый результат UC-3 (тест контроллера); раньше был только Timber. */
    val controllerTestRunning: Boolean = false,
    val controllerTestBanner: String? = null,
    val controllerTestIsError: Boolean = false,
 /** Последний текст статуса aQsi USB терминала. */
    val paymentTerminalTestBanner: String? = null,
 // Интеграции (модуль C )
    val maxExtApiToken: String = "",
    val maxVerificationDetailsEnabled: Boolean = false,
    val sbpSpotId: String = "",
    val sbpKey: String = "",
    val sbpTimeoutSec: String = "120",
    val nanoKassaId: String = "",
    val nanoKassaToken: String = "",
    val nanoKkt: String = "",
    val nanoAddress: String = "",
    val nanoPlace: String = "",
    val integrationsSaving: Boolean = false,
    val integrationsBanner: String? = null,
    val integrationsBannerIsError: Boolean = false,
 /** Последняя успешная verify Нанокассы (настройки → сохранить). */
    val nanoLastIntegrationVerifyOk: Boolean = false,
    val isDarkTheme: Boolean = false,
 /** ARGB бренда — светлая тема (сохраняется отдельно от тёмной). */
    val customerPrimaryLightArgb: Int = ViwaCustomerUiTokens.DefaultBrandPrimaryArgb,
 /** ARGB бренда — тёмная тема. */
    val customerPrimaryDarkArgb: Int = ViwaCustomerUiTokens.DefaultBrandPrimaryArgbDark,
 /** ARGB для слайдеров в настройках темы = значение активной темы. */
    val customerPrimaryButtonArgb: Int = ViwaCustomerUiTokens.DefaultBrandPrimaryArgb,
 /** Навигация сервисного меню (порядок групп. */
    val selectedServiceGroupId: ViwaServiceGroupId = ViwaServiceGroupId.Dashboard,
    val selectedServiceSubTabId: ViwaServiceSubTabId? = ViwaServiceSubTabId.DashboardOverview,
 /** Модуль D — телеметрия */
    val telemetryApiUrl: String = "",
    val telemetryWsUrl: String = "",
    val telemetryRegKey: String = "",
    val telemetrySerialConflict: Boolean = false,
    val telemetryRebindConfirmVisible: Boolean = false,
    val telemetrySerial: String = "",
    val telemetryPersistedSerial: String = "",
    val telemetryEnrolled: Boolean = false,
    val telemetrySerialNeedsRegistration: Boolean = false,
    val telemetryBusy: Boolean = false,
    val telemetryBanner: String? = null,
    val telemetryBannerIsError: Boolean = false,
    val telemetryQrScannedBanner: String? = null,
 /** Дебаг контроллера / оборудование — последний режим и ошибка (0x55 / 0x52). */
    val controllerDebugMode: Int? = null,
    val controllerDebugError: Int? = null,
    val controllerDebugWaterMl: Int? = null,
    val controllerDebugBusy: Boolean = false,
    val controllerDebugBanner: String? = null,
    val flowTemperatureSensor0C: Int? = null,
    val flowTemperatureSensor1C: Int? = null,
    val flowBucketIsFull: Boolean? = null,
 /** RGB ленты Flow (0xD2), ARGB; дефолт как быстрая кнопка «128,0,255». */
    val flowStripRgbArgb: Int = FlowStripRgbCoordinator.DEFAULT_FLOW_STRIP_RGB_ARGB,
    val controllerEquipmentConnectBusy: Boolean = false,
    val controllerEquipmentBanner: String? = null,
 /** Все USB-устройства; driverType == "—" означает неизвестный драйвер. */
    val controllerSerialPorts: List<UsbSerialDriverInfo> = emptyList(),
    val controllerAutoFindBusy: Boolean = false,
    val controllerAutoFindProgress: String? = null,
    val controllerAutoFindBanner: String? = null,
    val controllerAutoFindIsError: Boolean = false,
 /** USB serial сканер. */
    val scannerAvailablePorts: List<UsbSerialDriverInfo> = emptyList(),
    val scannerPortAssignments: Map<String, PortRole> = emptyMap(),
    val scannerDiscoveryResults: List<UsbSerialDriverInfo> = emptyList(),
    val isScannerDiscoveryRunning: Boolean = false,
 /** Отладочный RFID-ридер по UART/USB serial. */
    val rfidSerialPorts: List<UsbSerialDriverInfo> = emptyList(),
    val rfidConnectBusy: Boolean = false,
    val rfidConnected: Boolean = false,
    val rfidBanner: String? = null,
 /** G1 — калибровка воды */
    val waterCalTargetMlInput: String = "200",
    val waterCalActualMlInput: String = "",
    val waterCalInfo: WaterCalibrationData = WaterCalibrationData(),
    val waterCalPourBusy: Boolean = false,
    val waterCalSaveBusy: Boolean = false,
    val waterCalPourResult: String? = null,
    val waterCalBanner: String? = null,
    val waterCalBannerIsError: Boolean = false,
    val waterCalAdaptiveWindowInput: String = "2",
    val waterCalRecomputeBusy: Boolean = false,
 /** Аналитика времени готовки по напиткам (фактическое vs расчётное). */
    val preparingStatsDrinks: List<PreparingStatsDrinkOption> = emptyList(),
    val preparingStatsSelectedTasteId: Int? = null,
    val preparingStatsFlowRateMlPerSec: Double? = null,
    val preparingStatsHistory: List<PreparingStatsHistoryRow> = emptyList(),
    val preparingStatsBusy: Boolean = false,
    val preparingStatsBanner: String? = null,
    val preparingStatsBannerIsError: Boolean = false,
 /** Минуты до авто-выхода с экрана готовки; 0 = выкл. Поле ввода в «Время готовки». */
    val preparingAutoExitMinutesInput: String = "5",
    val preparingAutoExitSaveBanner: String? = null,
    val preparingAutoExitSaveBannerIsError: Boolean = false,
 /** G2 — калибровка сиропов */
    val syrupContainers: List<ContainerCalibrationInfo> = emptyList(),
    val syrupSelectedContainerNumber: Int? = null,
    val syrupTargetMlText: String = "30",
    val syrupActualMlText: String = "",
    val syrupNewConversionFactor: Double? = null,
    val syrupPourBusy: Boolean = false,
    val syrupSaveBusy: Boolean = false,
    val syrupBanner: String? = null,
    val syrupBannerIsError: Boolean = false,
 /** Пульс основной кнопки на экране напитков (Производительность → Анимации). */
    val primaryButtonPulseStyle: PrimaryButtonPulseStyle = PrimaryButtonPulseStyle.PulseScale,
 /** Режим отладки подписки: показывает FAB на экране напитков и доступ к тест-кнопке. */
    val subscriptionDebugEnabled: Boolean = false,
 /** Статус последней тестовой отправки statusSubscribeTopic. */
    val subscriptionDebugSendResult: String? = null,
    val subscriptionDebugSendBusy: Boolean = false,
)

@HiltViewModel
class ServiceViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val configRepository: ConfigRepository,
    private val updateRepository: UpdateRepository,
    private val controllerGateway: ControllerGateway,
    private val flowTemperatureStore: FlowTemperatureStore,
    private val controllerHardware: ControllerHardwareManager,
    private val drinkPreparing: ViwaDrinkPreparingService,
    private val waterCounter: ViwaWaterCounterService,
    private val aqsiUsbPaymentManager: AqsiUsbPaymentManager,
    private val controllerSbpNotifyService: ControllerSbpNotifyService,
    private val maxRepository: MaxRepository,
    private val sbpRepository: SBPRepository,
    private val nanoKassaRepository: NanoKassaRepository,
    private val telemetryService: ViwaTelemetryService,
    private val networkTrafficLogger: NetworkTrafficLogger,
    private val controllerTrafficLogger: ViwaControllerTrafficLogger,
    private val controllerRawLogger: com.viwa.android.hardware.controller.ViwaControllerRawLogger,
    private val syrupCalibrationInventory: SyrupCalibrationInventory,
    private val telemetryCellsRepository: TelemetryCellsRepository,
    private val telemetryCellsSyncCoordinator: TelemetryCellsSyncCoordinator,
    private val syrupCalibrationService: SyrupCalibrationService,
    private val themeRepository: ThemeRepository,
    private val serialPortManager: SerialPortManager,
    private val scannerManager: ScannerManager,
    private val scannerTrafficLogger: ViwaScannerTrafficLogger,
    private val rfidProbeManager: ViwaRfidProbeManager,
    private val rfidTrafficLogger: ViwaRfidTrafficLogger,
    private val waterCalibrationService: WaterCalibrationService,
    private val preparingTimeHistoryStore: PreparingTimeHistoryStore,
    private val flowStripRgbCoordinator: FlowStripRgbCoordinator,
    private val telemetryRegistrationScannerCoordinator: TelemetryRegistrationScannerCoordinator,
    private val technicianServiceMenuAccess: com.viwa.android.domain.technician.TechnicianServiceMenuAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(ServiceUiState())
    val state: StateFlow<ServiceUiState> = _state.asStateFlow()

    private val _telemetryConnectionUi = MutableStateFlow(TelemetryConnectionUiState())
    val telemetryConnectionUi: StateFlow<TelemetryConnectionUiState> = _telemetryConnectionUi.asStateFlow()

    private val _mvpInventoryRows = MutableStateFlow<List<MvpInventoryTableRow>>(emptyList())
    val mvpInventoryRows: StateFlow<List<MvpInventoryTableRow>> = _mvpInventoryRows.asStateFlow()

    private val _snapshotProducts = MutableStateFlow<List<TelemetryProduct>>(emptyList())
    val snapshotProducts: StateFlow<List<TelemetryProduct>> = _snapshotProducts.asStateFlow()

    private val _dashboardCells = MutableStateFlow<List<ServiceDashboardCellUi>>(emptyList())
    val dashboardCells: StateFlow<List<ServiceDashboardCellUi>> = _dashboardCells.asStateFlow()

    private val _totalWaterUsageMl = MutableStateFlow(0.0)
    val totalWaterUsageMl: StateFlow<Double> = _totalWaterUsageMl.asStateFlow()

 /** USB/Native UART контроллера открыт (не мок). */
    val controllerPhysicalConnected: StateFlow<Boolean> = controllerGateway.isPhysicalControllerConnected

 /** USB-serial сканера штрихкодов/QR открыт и читается ([ScannerManager]). */
    val scannerSerialActive: StateFlow<Boolean> = scannerManager.scannerSerialActive

    private val _terminalVendStatusLine = MutableStateFlow("Инициализация")
    val terminalVendStatusLine: StateFlow<String> = _terminalVendStatusLine.asStateFlow()

    private val _updateInstallProgress = MutableStateFlow<UpdateProgress?>(null)
    val updateInstallProgress: StateFlow<UpdateProgress?> = _updateInstallProgress.asStateFlow()

    val networkTrafficFlow: StateFlow<List<NetworkTrafficEntry>> = networkTrafficLogger.entries

    val controllerTrafficFlow = controllerTrafficLogger.entries
    val controllerRawTrafficFlow = controllerRawLogger.entries

    val scannerTrafficFlow = scannerTrafficLogger.entries

    val rfidTrafficFlow = rfidTrafficLogger.entries

    /** Active technician session (role/scopes) for service-menu access layer — no UI binding required. */
    val technicianSession = technicianServiceMenuAccess.session

    fun isServiceMenuAuthorized(): Boolean = technicianServiceMenuAccess.isAuthorized()

    private val _usbSerialDevices =
        MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val usbSerialDevices: StateFlow<List<Pair<String, String>>> = _usbSerialDevices.asStateFlow()

    private val _equipmentSelectedUsbPath = MutableStateFlow<String>("")
    val equipmentSelectedUsbPath: StateFlow<String> = _equipmentSelectedUsbPath.asStateFlow()

    private val _rfidSelectedUsbPath = MutableStateFlow<String>("")
    val rfidSelectedUsbPath: StateFlow<String> = _rfidSelectedUsbPath.asStateFlow()

    private var scannerDiscoveryCollectJob: Job? = null

    @Volatile
    private var telemetryConnectionTabActive = false

    init {
        observeUpdateProgress()
        observeOtaCoordinator()
        loadServiceState()
        loadIntegrationSettings()
        observeTerminalVendStatus()
        loadTelemetryForm()
        observeTelemetryConnection()
        observeTelemetryRegistrationScans()
        observeInventoryTable()
        refreshSyrupCalibrationRows()
        refreshPreparingStatsData()
        observeControllerResponsesForDebug()
        observeRfidProbeConnection()
        refreshUsbSerialDevicesList()
        viewModelScope.launch {
            combine(
                themeRepository.isDark,
                themeRepository.customerPrimaryLightArgb,
                themeRepository.customerPrimaryDarkArgb,
            ) { dark, lightArgb, darkArgb ->
                Triple(dark, lightArgb, darkArgb)
            }.collect { (dark, lightArgb, darkArgb) ->
                _state.update {
                    it.copy(
                        isDarkTheme = dark,
                        customerPrimaryLightArgb = lightArgb,
                        customerPrimaryDarkArgb = darkArgb,
                        customerPrimaryButtonArgb = if (dark) darkArgb else lightArgb,
                    )
                }
            }
        }
        viewModelScope.launch {
            val saved = configRepository.get(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH)?.trim().orEmpty()
            if (saved.isNotEmpty()) _equipmentSelectedUsbPath.value = saved
        }
        viewModelScope.launch {
            val saved = configRepository.get(JsonStoreKeys.RFID_READER_DEVICE_PATH)?.trim().orEmpty()
            if (saved.isNotEmpty()) _rfidSelectedUsbPath.value = saved
        }
    }

    private fun observeControllerResponsesForDebug() {
        viewModelScope.launch {
            controllerHardware.incomingResponses.collect { ev ->
                when (ev.response) {
                    ResponseCommand.AutoChangeDeviceMode ->
                        if (ev.payload.isNotEmpty()) {
                            _state.update {
                                it.copy(controllerDebugMode = ev.payload[0].toInt() and 0xff)
                            }
                        }
                    ResponseCommand.ControllerMsgError ->
                        if (ev.payload.isNotEmpty()) {
                            _state.update {
                                it.copy(controllerDebugError = ev.payload[0].toInt() and 0xff)
                            }
                        }
                    ResponseCommand.ControllerTimeoutResetActivate ->
                        if (ev.payload.size >= 2) {
                            val t0 = decodeFlowTemperatureByte(ev.payload[0].toInt() and 0xff)
                            val t1 = decodeFlowTemperatureByte(ev.payload[1].toInt() and 0xff)
                            flowTemperatureStore.update(t0, t1)
                            _state.update {
                                it.copy(
                                    flowTemperatureSensor0C = t0,
                                    flowTemperatureSensor1C = t1,
                                    controllerDebugBanner = "Flow температура: T0=${t0}°C · T1=${t1}°C",
                                )
                            }
                        }
                    ResponseCommand.CupSensorStatusAnswer ->
                        if (ev.payload.isNotEmpty()) {
                            val full = (ev.payload[0].toInt() and 0xff) != 0
                            _state.update {
                                it.copy(
                                    flowBucketIsFull = full,
                                    controllerDebugBanner = if (full) "Flow ведро: заполнено" else "Flow ведро: не заполнено",
                                )
                            }
                        }
                    else -> Unit
                }
            }
        }
    }

    private fun observeRfidProbeConnection() {
        viewModelScope.launch {
            rfidProbeManager.isConnected.collect { connected ->
                _state.update { it.copy(rfidConnected = connected) }
            }
        }
    }

    fun refreshUsbSerialDevicesList() {
        viewModelScope.launch {
            val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            _usbSerialDevices.value =
                usb.deviceList.values.map { dev ->
                    dev.deviceName to "VID ${dev.vendorId} · PID ${dev.productId}"
                }
        }
    }

    private fun detectSerialPorts(): List<UsbSerialDriverInfo> {
        val result = mutableListOf<UsbSerialDriverInfo>()

        NativeSerialPortDetector.detectPortPaths().forEach { path ->
            result.add(
                UsbSerialDriverInfo(
                    deviceName = path,
                    vendorId = 0,
                    productId = 0,
                    driverType = "Native UART",
                ),
            )
        }

        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val prober = UsbSerialProber.getDefaultProber()
        usb.deviceList.values.forEach { device ->
            val driver = prober.probeDevice(device)
            result.add(
                UsbSerialDriverInfo(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    driverType = driver?.let { it::class.simpleName ?: "Serial" } ?: "—",
                ),
            )
        }

        return result
    }

    fun refreshControllerSerialPorts() {
        viewModelScope.launch {
            _state.update { it.copy(controllerSerialPorts = detectSerialPorts()) }
        }
    }

    fun refreshRfidSerialPorts() {
        viewModelScope.launch {
            _state.update { it.copy(rfidSerialPorts = detectSerialPorts()) }
        }
    }

    fun autoFindController() {
        viewModelScope.launch {
            val nativeCandidates = NativeSerialPortDetector.detectPortPaths()

 // USB serial с распознанным драйвером
            val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val prober = UsbSerialProber.getDefaultProber()
            val usbCandidates = usb.deviceList.values
                .filter { prober.probeDevice(it) != null }
                .map { it.deviceName }

            val candidates = nativeCandidates + usbCandidates

            if (candidates.isEmpty()) {
                _state.update {
                    it.copy(
                        controllerAutoFindBusy = false,
                        controllerAutoFindProgress = null,
                        controllerAutoFindBanner = "Нет устройств с распознанным serial-драйвером",
                        controllerAutoFindIsError = true,
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    controllerAutoFindBusy = true,
                    controllerAutoFindProgress = null,
                    controllerAutoFindBanner = null,
                    controllerAutoFindIsError = false,
                )
            }

            var found = false
            for ((index, path) in candidates.withIndex()) {
                _state.update {
                    it.copy(controllerAutoFindProgress = "${index + 1}/${candidates.size}  $path")
                }
                val result = runCatching { controllerHardware.connectToPort(path) }.getOrNull()
                if (result?.success == true) {
                    _equipmentSelectedUsbPath.value = path
                    val connectMsg = controllerConnectSuccessMessage(result)
                    _state.update {
                        it.copy(
                            controllerAutoFindBusy = false,
                            controllerAutoFindProgress = null,
                            controllerAutoFindBanner = "Контроллер найден: $path · $connectMsg",
                            controllerAutoFindIsError = false,
                            controllerEquipmentBanner = connectMsg,
                        )
                    }
                    found = true
                    break
                }
            }

            if (!found) {
                _state.update {
                    it.copy(
                        controllerAutoFindBusy = false,
                        controllerAutoFindProgress = null,
                        controllerAutoFindBanner = "Контроллер не найден (проверено ${candidates.size} портов)",
                        controllerAutoFindIsError = true,
                    )
                }
            }
        }
    }

    fun setEquipmentSelectedUsbPath(path: String) {
        _equipmentSelectedUsbPath.value = path
    }

    fun setRfidSelectedUsbPath(path: String) {
        _rfidSelectedUsbPath.value = path
    }

    private fun controllerConnectSuccessMessage(r: ConnectToPortResult): String {
        val parts =
            buildList {
                r.firmware?.let { add("прошивка $it") }
                r.errorCode?.let { add("ошибка $it") }
                r.mode?.let { add("режим $it") }
            }
        return if (parts.isEmpty()) {
            "Подключено (порт открыт)"
        } else {
            "Подключено: ${parts.joinToString(", ")}"
        }
    }

    private fun UsbSerialDriver.toInfo(): UsbSerialDriverInfo =
        UsbSerialDriverInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            driverType = this::class.simpleName ?: "Unknown",
        )

    fun loadScannerPorts() {
        viewModelScope.launch {
            try {
                val drivers = serialPortManager.getAvailablePorts()
                val assignments = serialPortManager.getPortAssignments()
                _state.update {
                    it.copy(
                        scannerAvailablePorts = drivers.map { d -> d.toInfo() },
                        scannerPortAssignments = assignments,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "loadScannerPorts failed")
            }
        }
    }

    fun setScannerPortRole(deviceName: String, role: PortRole) {
        viewModelScope.launch {
            serialPortManager.setPortAssignment(deviceName, role)
            loadScannerPorts()
        }
    }

    fun startScannerDiscovery() {
        scannerDiscoveryCollectJob?.cancel()
        serialPortManager.startScannerDiscovery()
        _state.update { it.copy(isScannerDiscoveryRunning = true) }
        scannerDiscoveryCollectJob =
            viewModelScope.launch {
                try {
                    serialPortManager.scannerDiscoveryFlow.collect { drivers ->
                        _state.update { s ->
                            s.copy(scannerDiscoveryResults = drivers.map { d -> d.toInfo() })
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) Timber.e(e, "scannerDiscoveryFlow")
                }
            }
    }

    fun stopScannerDiscovery() {
        scannerDiscoveryCollectJob?.cancel()
        scannerDiscoveryCollectJob = null
        serialPortManager.stopScannerDiscovery()
        _state.update { it.copy(isScannerDiscoveryRunning = false) }
    }

    fun onEquipmentScannerTabVisible() {
        loadScannerPorts()
    }

    fun onEquipmentScannerTabHidden() {
        stopScannerDiscovery()
    }

    fun clearScannerTrafficLog() {
        scannerTrafficLogger.clear()
    }

    override fun onCleared() {
        scannerDiscoveryCollectJob?.cancel()
        serialPortManager.stopScannerDiscovery()
        rfidProbeManager.disconnect()
        super.onCleared()
    }

    fun clearControllerTrafficLog() {
        controllerTrafficLogger.clear()
    }

    fun clearControllerRawLog() {
        controllerRawLogger.clear()
    }

    fun connectControllerUsb() {
        viewModelScope.launch {
            val path = _equipmentSelectedUsbPath.value.trim()
            if (path.isEmpty()) {
                _state.update {
                    it.copy(controllerEquipmentBanner = "Выберите USB-устройство")
                }
                return@launch
            }
            _state.update {
                it.copy(controllerEquipmentConnectBusy = true, controllerEquipmentBanner = null)
            }
            runCatching {
                val r = controllerHardware.connectToPort(path)
                _state.update {
                    it.copy(
                        controllerEquipmentConnectBusy = false,
                        controllerEquipmentBanner =
                            if (r.success) {
                                controllerConnectSuccessMessage(r)
                            } else {
                                "Ошибка: step=${r.failedStep} fw=${r.firmware} err=${r.errorCode} mode=${r.mode}"
                            },
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        controllerEquipmentConnectBusy = false,
                        controllerEquipmentBanner = e.message ?: "Ошибка подключения",
                    )
                }
            }
        }
    }

    fun connectRfidReader() {
        viewModelScope.launch {
            val path = _rfidSelectedUsbPath.value.trim()
            if (path.isEmpty()) {
                _state.update { it.copy(rfidBanner = "Выберите serial-порт RFID-ридера") }
                return@launch
            }
            _state.update { it.copy(rfidConnectBusy = true, rfidBanner = null) }
            runCatching {
                val ok = rfidProbeManager.connect(path)
                if (ok) {
                    configRepository.set(JsonStoreKeys.RFID_READER_DEVICE_PATH, path)
                    _state.update {
                        it.copy(
                            rfidConnectBusy = false,
                            rfidConnected = true,
                            rfidBanner = "RFID-ридер подключён: $path",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            rfidConnectBusy = false,
                            rfidConnected = false,
                            rfidBanner = "Не удалось открыть порт RFID-ридера",
                        )
                    }
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        rfidConnectBusy = false,
                        rfidConnected = false,
                        rfidBanner = e.message ?: "Ошибка подключения RFID-ридера",
                    )
                }
            }
        }
    }

    fun disconnectRfidReader() {
        rfidProbeManager.disconnect()
        _state.update {
            it.copy(
                rfidConnectBusy = false,
                rfidConnected = false,
                rfidBanner = "RFID-ридер отключён",
            )
        }
    }

    fun onEquipmentRfidTabVisible() {
        refreshRfidSerialPorts()
    }

    fun clearRfidTrafficLog() {
        rfidTrafficLogger.clear()
    }

    fun controllerDebugReconnect() {
        viewModelScope.launch {
            runCatching { controllerHardware.reconnect() }
        }
    }

    fun controllerDebugCheck() {
        viewModelScope.launch {
            controllerGateway.sendCommand(
                RequestCommand.ReadFirmwareVersion,
                byteArrayOf(0, 0, 0, 0, 0),
            )
        }
    }

    fun controllerDebugReadErrors() {
        viewModelScope.launch {
            _state.update { it.copy(controllerDebugBusy = true, controllerDebugBanner = null) }
            runCatching {
                val code = controllerHardware.readControllerErrorsCode()
                _state.update {
                    it.copy(
                        controllerDebugBusy = false,
                        controllerDebugError = code,
                        controllerDebugBanner = "Ошибка контроллера: $code",
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        controllerDebugBusy = false,
                        controllerDebugBanner = e.message,
                    )
                }
            }
        }
    }

    fun controllerDebugReadMode() {
        viewModelScope.launch {
            _state.update { it.copy(controllerDebugBusy = true, controllerDebugBanner = null) }
            runCatching {
                val m = controllerHardware.readDeviceModeByte()
                _state.update {
                    it.copy(
                        controllerDebugBusy = false,
                        controllerDebugMode = m.takeIf { it >= 0 },
                        controllerDebugBanner = "Режим: $m",
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(controllerDebugBusy = false, controllerDebugBanner = e.message)
                }
            }
        }
    }

    fun controllerDebugSendRecipe() {
        viewModelScope.launch {
            val body =
                ChooseDrinkBodyBuilder.build(
                    physicalPort = 9,
                    dispenserWorkTimeSec = 3.0,
                    waterMl = 20.0,
                    tof = 0,
                )
            controllerGateway.sendCommand(RequestCommand.ChooseDrink, body)
        }
    }

    fun controllerDebugStartPreparing() {
        drinkPreparing.startDrinkPreparing(10)
    }

    fun controllerDebugServiceMode() {
        viewModelScope.launch { controllerHardware.setServiceModeCommand() }
    }

    fun controllerDebugAutoMode() {
        viewModelScope.launch { controllerHardware.setAutoModeCommand() }
    }

    fun controllerDebugReadWaterCounter() {
        viewModelScope.launch {
            _state.update { it.copy(controllerDebugBusy = true) }
            runCatching {
                val ml = waterCounter.getWaterUsageMl()
                _state.update {
                    it.copy(
                        controllerDebugBusy = false,
                        controllerDebugWaterMl = ml,
                        controllerDebugBanner = "Счётчик воды: $ml мл",
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(controllerDebugBusy = false, controllerDebugBanner = e.message)
                }
            }
        }
    }

    fun controllerDebugResetWaterCounter() {
        viewModelScope.launch {
            runCatching {
                waterCounter.resetWaterUsage()
                _state.update {
                    it.copy(controllerDebugBanner = "Счётчик сброшен (ResetWaterCounter)")
                }
            }
        }
    }

    fun controllerDebugReadFlowTemperature() {
        viewModelScope.launch {
            controllerGateway.sendCommand(RequestCommand.ReadFlowTemperature, byteArrayOf(0, 0, 0, 0, 0))
        }
    }

    fun controllerDebugSetFlowRgb() {
        viewModelScope.launch {
            flowStripRgbCoordinator.sendFlowRgbArgb(FlowStripRgbCoordinator.DEFAULT_FLOW_STRIP_RGB_ARGB)
        }
    }

 /** Предпросмотр при движении ползунков (без записи в хранилище). */
    fun previewFlowStripRgb(r: Int, g: Int, b: Int) {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        val argb = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        _state.update { it.copy(flowStripRgbArgb = argb) }
    }

 /** Сохранение и отправка на контроллер при отпускании ползунка. */
    fun persistFlowStripRgb() {
        viewModelScope.launch {
            val argb = _state.value.flowStripRgbArgb
            configRepository.set(JsonStoreKeys.FLOW_STRIP_RGB_ARGB, argbToHex8Store(argb))
            flowStripRgbCoordinator.sendFlowRgbArgb(argb)
        }
    }

    fun resetFlowStripRgbToDefault() {
        viewModelScope.launch {
            configRepository.delete(JsonStoreKeys.FLOW_STRIP_RGB_ARGB)
            _state.update {
                it.copy(flowStripRgbArgb = FlowStripRgbCoordinator.DEFAULT_FLOW_STRIP_RGB_ARGB)
            }
            flowStripRgbCoordinator.sendFlowRgbArgb(FlowStripRgbCoordinator.DEFAULT_FLOW_STRIP_RGB_ARGB)
        }
    }

    fun controllerDebugReadFlowBucket() {
        viewModelScope.launch {
            controllerGateway.sendCommand(RequestCommand.ReadFlowBucketStatus, byteArrayOf(0, 0, 0, 0, 0))
        }
    }

    fun setUseMockController(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.set(
                JsonStoreKeys.USE_MOCK_CONTROLLER,
                if (enabled) "true" else "false",
            )
            _state.update { it.copy(useMockController = enabled) }
            runCatching { controllerHardware.initializeFromConfig() }
        }
    }

    private fun observeInventoryTable() {
        viewModelScope.launch {
            telemetryCellsRepository.snapshotFlow.collect { snapshot ->
                runCatching {
                    applyMvpInventorySnapshot(snapshot)
                    updateDashboardDerived(_mvpInventoryRows.value)
                    refreshSyrupCalibrationRows()
                    refreshPreparingStatsData()
                }.onFailure { Timber.e(it, "observeInventoryTable") }
            }
        }
    }

    private suspend fun applyMvpInventorySnapshot(snapshot: com.viwa.android.domain.model.TelemetryCellsSnapshot?) {
        val (rows, products) = mapMvpInventoryFromSnapshot(snapshot)
        _mvpInventoryRows.value = rows
        _snapshotProducts.value = products
    }

    fun refreshSyrupCalibrationUi() {
        refreshSyrupCalibrationRows()
    }

    private fun refreshSyrupCalibrationRows() {
        viewModelScope.launch {
            runCatching {
                val list = syrupCalibrationInventory.listContainersForCalibration()
                _state.update { s ->
                    val stillValid =
                        s.syrupSelectedContainerNumber?.let { sel ->
                            list.any { it.containerNumber == sel }
                        } ?: false
                    val nextSel =
                        when {
                            list.isEmpty() -> null
                            stillValid -> s.syrupSelectedContainerNumber
                            else -> list.first().containerNumber
                        }
                    s.copy(
                        syrupContainers = list,
                        syrupSelectedContainerNumber = nextSel,
                        syrupNewConversionFactor = null,
                    )
                }
            }.onFailure { Timber.e(it, "refreshSyrupCalibrationRows") }
        }
    }

    fun setSyrupSelectedContainerNumber(n: Int) {
        _state.update {
            it.copy(syrupSelectedContainerNumber = n, syrupNewConversionFactor = null, syrupBanner = null)
        }
    }

    fun setSyrupTargetMlText(text: String) {
        _state.update { it.copy(syrupTargetMlText = text) }
    }

    fun setSyrupActualMlText(text: String) {
        _state.update { it.copy(syrupActualMlText = text) }
    }

    fun runSyrupTestPour() {
        val sel = _state.value.syrupSelectedContainerNumber ?: return
        val targetMl = _state.value.syrupTargetMlText.toDoubleOrNull()
        if (targetMl == null || targetMl <= 0) {
            _state.update {
                it.copy(syrupBanner = "Введите целевой объём (мл) > 0", syrupBannerIsError = true)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(syrupPourBusy = true, syrupBanner = null) }
            val result = syrupCalibrationService.pourTestSample(sel, targetMl)
            _state.update {
                it.copy(
                    syrupPourBusy = false,
                    syrupBanner =
                        result.fold(
                            onSuccess = { "Тестовый налив отправлен (контейнер $sel)" },
                            onFailure = { e -> e.message ?: "Ошибка налива" },
                        ),
                    syrupBannerIsError = result.isFailure,
                )
            }
        }
    }

    fun saveSyrupCalibration() {
        val sel = _state.value.syrupSelectedContainerNumber ?: return
        val actual = _state.value.syrupActualMlText.toDoubleOrNull()
        if (actual == null || actual <= 0) {
            _state.update {
                it.copy(syrupBanner = "Введите фактический объём (мл) > 0", syrupBannerIsError = true)
            }
            return
        }
        val targetManual = _state.value.syrupTargetMlText.toDoubleOrNull()
        viewModelScope.launch {
            _state.update { it.copy(syrupSaveBusy = true, syrupBanner = null) }
            val result =
                syrupCalibrationService.submitCalibrationResult(
                    containerNumber = sel,
                    actualVolumeMl = actual,
                    targetProductMl = targetManual?.takeIf { it > 0 },
                )
            _state.update { s ->
                result.fold(
                    onSuccess = { newCf ->
                        val updatedList = syrupCalibrationInventory.listContainersForCalibration()
                        s.copy(
                            syrupSaveBusy = false,
                            syrupNewConversionFactor = newCf,
                            syrupContainers = updatedList,
                            syrupBanner = "Сохранено. Новый conversionFactor: ${"%.4f".format(newCf)}",
                            syrupBannerIsError = false,
                        )
                    },
                    onFailure = { e ->
                        s.copy(
                            syrupSaveBusy = false,
                            syrupBanner = e.message ?: "Ошибка сохранения",
                            syrupBannerIsError = true,
                        )
                    },
                )
            }
        }
    }

    fun refreshInventoryRows() {
        viewModelScope.launch {
            runCatching {
                applyMvpInventorySnapshot(telemetryCellsRepository.getSnapshot())
                updateDashboardDerived(_mvpInventoryRows.value)
            }.onFailure { Timber.e(it, "refreshInventoryRows") }
        }
    }

    private suspend fun updateDashboardDerived(rows: List<MvpInventoryTableRow>) {
        _totalWaterUsageMl.value = waterCounter.getAccumulatedWaterUsageMl()
        _dashboardCells.value = buildServiceDashboardCells(rows)
    }

 /** Обновить строки наполнения и блок дашборда (вода, ячейки). */
    fun refreshServiceDashboard() {
        refreshInventoryRows()
        refreshUsbSerialDevicesList()
    }

    fun saveInventoryVolumes(
        updates: List<CellVolumeUpdate>,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val snapshot =
                    telemetryCellsRepository.getSnapshot()
                        ?: error("Нет локального snapshot ячеек (ожидайте cells.snapshot по WS)")
                val wires =
                    updates.mapNotNull { update ->
                        val cell =
                            snapshot.cells.find { it.cellNumber == update.containerNumber }
                                ?: return@mapNotNull null
                        CellVolumeUpdateWire(uuid = cell.uuid, volume = update.volumeMl)
                    }
                if (wires.isEmpty()) error("Не найдены ячейки для сохранения объёмов")
                telemetryCellsSyncCoordinator.onLocalVolumeChange(wires)
            }.fold(
                onSuccess = { onDone(true, "Сохранено локально, cells.volume.report отправлен при подключении WS") },
                onFailure = { e ->
                    Timber.e(e, "saveInventoryVolumes")
                    onDone(false, e.message ?: "Ошибка сохранения")
                },
            )
        }
    }

    fun saveMvpInventoryContent(
        updates: List<MvpInventoryContentUpdate>,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val snapshot =
                    telemetryCellsRepository.getSnapshot()
                        ?: error("Нет локального snapshot ячеек")
                val updatedCells =
                    updates.map { update ->
                        val cell =
                            snapshot.cells.find { it.uuid == update.cellUuid }
                                ?: error("Ячейка ${update.cellUuid} не найдена")
                        val product = update.productUuid?.let { id -> snapshot.products.find { it.uuid == id } }
                        cell.copy(
                            productUuid = update.productUuid,
                            productName = product?.name ?: cell.productName,
                            tasteMediaKey = product?.tasteMediaKey ?: cell.tasteMediaKey,
                            dosage1Price = update.dosage1PriceKopecks,
                            dosage2Price = update.dosage2PriceKopecks,
                        )
                    }
                telemetryCellsSyncCoordinator.onLocalContentChange(updatedCells)
            }.fold(
                onSuccess = { onDone(true, "Сохранено локально, cells.content.report отправлен при подключении WS") },
                onFailure = { e ->
                    Timber.e(e, "saveMvpInventoryContent")
                    onDone(false, e.message ?: "Ошибка сохранения")
                },
            )
        }
    }

 /** Остатки → одна ячейка: объём = maxVolume из merge-конфига. */
    fun fillInventoryCellToMax(
        cellNumber: Int,
        maxVolumeMl: Int,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        saveInventoryVolumes(
            listOf(CellVolumeUpdate(containerNumber = cellNumber, volumeMl = maxVolumeMl.coerceAtLeast(0))),
            onDone,
        )
    }

 /** Остатки → прокачка сиропа фиксированным объёмом 30 мл для выбранной ячейки. */
    fun runInventorySyrupPrime(
        cellNumber: Int,
        onDone: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val syrupCellNumbers = _state.value.syrupContainers.map { it.containerNumber }.toSet()
            if (!syrupCellNumbers.contains(cellNumber)) {
                onDone(false, "Ячейка $cellNumber не является сиропом")
                return@launch
            }
            val result = syrupCalibrationService.pourTestSample(containerNumber = cellNumber, targetProductMl = 30.0)
            result.fold(
                onSuccess = { onDone(true, "Прокачка 30 мл отправлена (ячейка $cellNumber)") },
                onFailure = { e -> onDone(false, e.message ?: "Ошибка прокачки сиропа") },
            )
        }
    }

    private fun loadTelemetryForm() {
        viewModelScope.launch {
            runCatching {
                val t = telemetryService.loadTelemetryConfig()
                val r = telemetryService.loadMachineRegistration()
                _state.update {
                    it.copy(
                        telemetryApiUrl = t.apiUrl,
                        telemetryWsUrl = MvpTelemetryUrlResolver.displayWsUrl(t.apiUrl, t.wsUrl),
                        telemetryRegKey = r.regKey,
                        telemetrySerial = r.serialNumber,
                        telemetryPersistedSerial = r.serialNumber,
                        telemetryEnrolled = com.viwa.android.domain.model.MachineRegistration.isEnrolled(r),
                        telemetrySerialNeedsRegistration = false,
                        telemetrySerialConflict = false,
                        telemetryRebindConfirmVisible = false,
                    )
                }
            }.onFailure { Timber.e(it, "loadTelemetryForm") }
        }
    }

    private fun observeTelemetryConnection() {
        viewModelScope.launch {
            telemetryService.connectionState.collect { s ->
                val label =
                    when (s) {
                        ConnectionState.Connecting -> "MVP WS: подключение…"
                        ConnectionState.Connected -> "MVP WS: ONLINE"
                        is ConnectionState.Disconnected ->
                            if (s.retryInMs > 0) {
                                "WS: переподключение через ${s.retryInMs} ms"
                            } else {
                                "WS: отключено"
                            }
                        is ConnectionState.Error -> "WS: ошибка — ${s.message}"
                    }
                _telemetryConnectionUi.value =
                    TelemetryConnectionUiState(
                        label = label,
                        connected = s is ConnectionState.Connected,
                        error =
                            when (s) {
                                is ConnectionState.Error -> s.message
                                else -> null
                            },
                    )
            }
        }
    }

    fun onServiceGroupSelected(id: ViwaServiceGroupId) {
        val group = ViwaServiceMenuGroups.firstOrNull { it.id == id } ?: return
        val first = group.subTabs.firstOrNull()?.id
        _state.update {
            it.copy(
                selectedServiceGroupId = id,
                selectedServiceSubTabId = first,
            )
        }
    }

    fun onServiceSubTabSelected(id: ViwaServiceSubTabId) {
        _state.update { it.copy(selectedServiceSubTabId = id) }
    }

    fun setTelemetryApiUrl(v: String) {
        _state.update { it.copy(telemetryApiUrl = v, telemetryQrScannedBanner = null) }
    }

    fun setTelemetryWsUrl(v: String) {
        _state.update { it.copy(telemetryWsUrl = v) }
    }

    fun onTelemetryConnectionTabVisible() {
        telemetryConnectionTabActive = true
    }

    fun onTelemetryConnectionTabHidden() {
        telemetryConnectionTabActive = false
    }

    private fun observeTelemetryRegistrationScans() {
        viewModelScope.launch {
            telemetryRegistrationScannerCoordinator.scanEvents.collect { event ->
                if (!telemetryConnectionTabActive) return@collect
                applyTelemetryRegistrationScan(event)
            }
        }
    }

    private fun applyTelemetryRegistrationScan(event: TelemetryRegistrationScanUiEvent) {
        val result =
            TelemetryRegistrationScanApplier.apply(
                event = event,
                currentApiUrl = _state.value.telemetryApiUrl,
            )
        setTelemetryRegKey(result.regKey)
        result.apiUrl?.let { setTelemetryApiUrl(it) }
        result.serial?.let { setTelemetrySerial(it) }
        _state.update {
            it.copy(
                telemetryQrScannedBanner = result.qrBanner,
                telemetryBanner = result.urlWarning,
                telemetryBannerIsError = false,
            )
        }
        if (TelemetryRegistrationScanApplier.shouldAutoRegister(result)) {
            registerTelemetryMachine()
        }
    }

    fun isTelemetryFreeSerialUiVisible(): Boolean = TelemetryRegistrationUiPolicy.showReserveFreeSerialButton()

    fun setTelemetryRegKey(v: String) {
        _state.update { it.copy(telemetryRegKey = v, telemetryQrScannedBanner = null) }
    }

    fun setTelemetrySerial(v: String) {
        _state.update {
            it.copy(
                telemetrySerial = v,
                telemetrySerialConflict = false,
                telemetryRebindConfirmVisible = false,
                telemetryQrScannedBanner = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                val changed = telemetryService.applyUiSerialChange(v)
                if (changed) {
                    val r = telemetryService.loadMachineRegistration()
                    _state.update {
                        it.copy(
                            telemetryPersistedSerial = r.serialNumber,
                            telemetryEnrolled = com.viwa.android.domain.model.MachineRegistration.isEnrolled(r),
                            telemetrySerialNeedsRegistration = true,
                            telemetryBanner = "Серийный номер изменён — требуется повторная регистрация",
                            telemetryBannerIsError = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            telemetrySerialNeedsRegistration =
                                !it.telemetryEnrolled &&
                                SerialNumberUtils.normalize(it.telemetrySerial) ==
                                    SerialNumberUtils.normalize(it.telemetryPersistedSerial),
                        )
                    }
                }
            }.onFailure { Timber.e(it, "setTelemetrySerial") }
        }
    }

    fun requestTelemetryFreeSerial() {
        viewModelScope.launch {
            _state.update { it.copy(telemetryBusy = true, telemetryBanner = null, telemetrySerialConflict = false) }
            runCatching { telemetryService.reserveFreeSerial().getOrThrow() }
                .onSuccess { serial ->
                    loadTelemetryForm()
                    _state.update {
                        it.copy(
                            telemetryBusy = false,
                            telemetrySerial = serial,
                            telemetryBanner = "Свободный serial: $serial",
                            telemetryBannerIsError = false,
                        )
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "requestTelemetryFreeSerial")
                    _state.update {
                        it.copy(
                            telemetryBusy = false,
                            telemetryBanner = e.message ?: "Не удалось запросить serial",
                            telemetryBannerIsError = true,
                        )
                    }
                }
        }
    }

    fun dismissTelemetryRebindConfirm() {
        _state.update {
            it.copy(
                telemetrySerialConflict = false,
                telemetryRebindConfirmVisible = false,
            )
        }
    }

    fun confirmTelemetryRebindAndRegister() {
        registerTelemetryMachine(rebind = true)
    }

    fun provisionTelemetryMachine() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    telemetryBusy = true,
                    telemetryBanner = null,
                    telemetrySerialConflict = false,
                    telemetryRebindConfirmVisible = false,
                )
            }
            runCatching {
                telemetryService.provisionMachine().getOrThrow()
                loadTelemetryForm()
                telemetryService.connect()
                val serial = SerialNumberUtils.normalize(_state.value.telemetrySerial)
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryEnrolled = true,
                        telemetrySerialNeedsRegistration = false,
                        telemetryBanner = "Авторегистрация OK: $serial; WS…",
                        telemetryBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "provisionTelemetryMachine")
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = e.message ?: "Ошибка авторегистрации",
                        telemetryBannerIsError = true,
                    )
                }
            }
        }
    }

    fun registerTelemetryMachine(rebind: Boolean = false) {
        val current = _state.value
        val validationMessage = SerialNumberUtils.validationMessage(current.telemetrySerial)
        if (validationMessage != null) {
            _state.update {
                it.copy(
                    telemetryBanner = validationMessage,
                    telemetryBannerIsError = true,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    telemetryBusy = true,
                    telemetryBanner = null,
                    telemetrySerialConflict = false,
                    telemetryRebindConfirmVisible = false,
                )
            }
            runCatching {
                val s = _state.value
                telemetryService.registerMachine(s.telemetryRegKey, s.telemetrySerial, rebind).getOrThrow()
                loadTelemetryForm()
                telemetryService.connect()
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryEnrolled = true,
                        telemetrySerialNeedsRegistration = false,
                        telemetryBanner = "Регистрация OK; запущено подключение WS",
                        telemetryBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "registerTelemetryMachine")
                val conflict = e as? SerialAlreadyBoundException ?: e.cause as? SerialAlreadyBoundException
                when {
                    conflict != null ->
                        _state.update {
                            it.copy(
                                telemetryBusy = false,
                                telemetrySerialConflict = true,
                                telemetryRebindConfirmVisible = true,
                                telemetryBanner =
                                    "Serial ${conflict.serialNumber} уже привязан к другой плате. " +
                                        "Подтвердите перепривязку (rebind) для замены credential.",
                                telemetryBannerIsError = true,
                            )
                        }
                    e is RebindNotAllowedException || e.cause is RebindNotAllowedException ->
                        _state.update {
                            it.copy(
                                telemetryBusy = false,
                                telemetryBanner = (e as? RebindNotAllowedException ?: e.cause as RebindNotAllowedException)
                                    ?.message
                                    ?: "Перепривязка запрещена.",
                                telemetryBannerIsError = true,
                            )
                        }
                    e is RegistrationKeyException || e.cause is RegistrationKeyException ->
                        _state.update {
                            it.copy(
                                telemetryBusy = false,
                                telemetryBanner = (e as? RegistrationKeyException ?: e.cause as RegistrationKeyException)
                                    ?.message
                                    ?: "Ошибка ключа регистрации.",
                                telemetryBannerIsError = true,
                            )
                        }
                    else ->
                        _state.update {
                            it.copy(
                                telemetryBusy = false,
                                telemetryBanner = e.message ?: "Ошибка регистрации",
                                telemetryBannerIsError = true,
                            )
                        }
                }
            }
        }
    }

    fun saveTelemetryEndpoints() {
        viewModelScope.launch {
            _state.update { it.copy(telemetryBusy = true, telemetryBanner = null) }
            runCatching {
                saveTelemetryEndpointsInternal()
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = "Конечные точки телеметрии сохранены",
                        telemetryBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "saveTelemetryEndpoints")
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = e.message ?: "Ошибка",
                        telemetryBannerIsError = true,
                    )
                }
            }
        }
    }

    fun connectTelemetry() {
        viewModelScope.launch {
            _state.update { it.copy(telemetryBusy = true, telemetryBanner = null) }
            runCatching {
                saveTelemetryEndpointsInternal()
                telemetryService.connectWithGuard(_state.value.telemetrySerial).getOrThrow()
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = "Подключение WS…",
                        telemetryBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "connectTelemetry")
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = e.message ?: "Не удалось подключить WS",
                        telemetryBannerIsError = true,
                    )
                }
            }
        }
    }

    fun disconnectTelemetry() {
        telemetryService.disconnect()
        _state.update {
            it.copy(
                telemetryBanner = "WebSocket отключён. Для повторного подключения нажмите «Подключить WS».",
                telemetryBannerIsError = false,
            )
        }
    }

    fun telemetryCanConnect(state: ServiceUiState): Boolean {
        if (state.telemetryBusy || state.telemetrySerial.isBlank()) {
            return false
        }
        val normUi = SerialNumberUtils.normalize(state.telemetrySerial)
        val normPersisted = SerialNumberUtils.normalize(state.telemetryPersistedSerial)
        if (normUi != normPersisted) {
            return false
        }
        return state.telemetryEnrolled && !state.telemetrySerialNeedsRegistration
    }

    fun telemetrySerialMismatch(state: ServiceUiState): Boolean {
        val normUi = SerialNumberUtils.normalize(state.telemetrySerial)
        val normPersisted = SerialNumberUtils.normalize(state.telemetryPersistedSerial)
        return normUi.isNotBlank() &&
            normPersisted.isNotBlank() &&
            normUi != normPersisted
    }

    fun clearNetworkTrafficLog() {
        networkTrafficLogger.clear()
    }

    fun reconnectTelemetry() {
        viewModelScope.launch {
            saveTelemetryEndpointsInternal()
            telemetryService.reconnect()
        }
    }

    private suspend fun saveTelemetryEndpointsInternal() {
        val s = _state.value
        telemetryService.saveTelemetryConfig(
            TelemetryConfig(
                apiUrl = s.telemetryApiUrl.trim().ifBlank { TelemetryConfig().apiUrl },
                wsUrl = TelemetryConfig.sanitizeWsUrl(s.telemetryWsUrl.trim()),
            ),
        )
    }

    private suspend fun runTelemetryTestAction(
        label: String,
        block: suspend () -> Unit,
    ) {
        _state.update { it.copy(telemetryBusy = true, telemetryBanner = null) }
        runCatching { block() }
            .onSuccess {
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = "Отправлено: $label. Ответ смотрите в «Логи сети» и logcat (ViwaTelemetry).",
                        telemetryBannerIsError = false,
                    )
                }
            }
            .onFailure { e ->
                Timber.e(e, "telemetryTestAction $label")
                _state.update {
                    it.copy(
                        telemetryBusy = false,
                        telemetryBanner = e.message ?: "Ошибка: $label",
                        telemetryBannerIsError = true,
                    )
                }
            }
    }

    private fun loadIntegrationSettings() {
        viewModelScope.launch {
            runCatching {
                val max = maxRepository.getSettings()
                val sbp = sbpRepository.getSettings()
                val nano = nanoKassaRepository.getSettings()
                _state.update {
                    it.copy(
                        maxExtApiToken = max.extApiToken,
                        maxVerificationDetailsEnabled = max.verificationDetailsEnabled,
                        sbpSpotId = sbp.spotId,
                        sbpKey = sbp.key,
                        sbpTimeoutSec = sbp.timeoutInSeconds.toString(),
                        nanoKassaId = nano.kassaId,
                        nanoKassaToken = nano.kassaToken,
                        nanoKkt = nano.kkt,
                        nanoAddress = nano.address,
                        nanoPlace = nano.place,
                        nanoLastIntegrationVerifyOk = nano.lastIntegrationVerifyOk,
                    )
                }
            }.onFailure { e -> Timber.e(e, "loadIntegrationSettings failed") }
        }
    }

    fun setMaxExtApiToken(value: String) {
        _state.update { it.copy(maxExtApiToken = value) }
    }

    fun setMaxVerificationDetailsEnabled(value: Boolean) {
        _state.update { it.copy(maxVerificationDetailsEnabled = value) }
    }

    fun setSbpSpotId(value: String) {
        _state.update { it.copy(sbpSpotId = value) }
    }

    fun setSbpKey(value: String) {
        _state.update { it.copy(sbpKey = value) }
    }

    fun setSbpTimeoutSec(value: String) {
        _state.update { it.copy(sbpTimeoutSec = value) }
    }

    fun setNanoKassaId(value: String) {
        _state.update { it.copy(nanoKassaId = value) }
    }

    fun setNanoKassaToken(value: String) {
        _state.update { it.copy(nanoKassaToken = value) }
    }

    fun setNanoKkt(value: String) {
        _state.update { it.copy(nanoKkt = value) }
    }

    fun setNanoAddress(value: String) {
        _state.update { it.copy(nanoAddress = value) }
    }

    fun setNanoPlace(value: String) {
        _state.update { it.copy(nanoPlace = value) }
    }

    fun saveMaxIntegrationSettings() {
        viewModelScope.launch {
            _state.update { it.copy(integrationsSaving = true, integrationsBanner = null) }
            runCatching {
                val s = _state.value
                maxRepository.updateSettings(
                    MaxSettings(
                        extApiToken = s.maxExtApiToken,
                        verificationDetailsEnabled = s.maxVerificationDetailsEnabled,
                    ),
                )
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = "MAX: настройки сохранены",
                        integrationsBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "saveMaxIntegrationSettings")
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = e.message ?: "Ошибка MAX",
                        integrationsBannerIsError = true,
                    )
                }
            }
        }
    }

    fun saveSbpIntegrationSettings() {
        viewModelScope.launch {
            _state.update { it.copy(integrationsSaving = true, integrationsBanner = null) }
            runCatching {
                val s = _state.value
                val timeout = s.sbpTimeoutSec.toIntOrNull() ?: 120
                val current = sbpRepository.getSettings()
                sbpRepository.updateSettings(
                    current.copy(
                        spotId = s.sbpSpotId,
                        key = s.sbpKey,
                        timeoutInSeconds = timeout,
                    ),
                )
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = "СБП: настройки сохранены",
                        integrationsBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "saveSbpIntegrationSettings")
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = e.message ?: "Ошибка СБП",
                        integrationsBannerIsError = true,
                    )
                }
            }
        }
    }

    fun saveNanoKassaIntegrationSettings() {
        viewModelScope.launch {
            _state.update { it.copy(integrationsSaving = true, integrationsBanner = null) }
            runCatching {
                val s = _state.value
                val prev = nanoKassaRepository.getSettings()
                nanoKassaRepository.updateSettings(
                    NanoKassaSettings(
                        kassaId = s.nanoKassaId,
                        kassaToken = s.nanoKassaToken,
                        kkt = s.nanoKkt,
                        address = s.nanoAddress,
                        place = s.nanoPlace,
                        lastIntegrationVerifyOk = false,
                        lastVerifyAtEpochMs = prev.lastVerifyAtEpochMs,
                    ),
                )
                nanoKassaRepository.verifyIntegration().getOrThrow()
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = "Нанокасса: проверка пройдена, настройки сохранены",
                        integrationsBannerIsError = false,
                        nanoLastIntegrationVerifyOk = true,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "saveNanoKassaIntegrationSettings")
                _state.update {
                    it.copy(
                        integrationsSaving = false,
                        integrationsBanner = e.message ?: "Ошибка Нанокассы",
                        integrationsBannerIsError = true,
                    )
                }
            }
        }
    }

    private fun observeTerminalVendStatus() {
        viewModelScope.launch {
            try {
                aqsiUsbPaymentManager.terminalStatusFlow.collect { text ->
                    _terminalVendStatusLine.value = text
                }
            } catch (e: Exception) {
                Timber.e(e, "observeTerminalVendStatus failed")
            }
        }
    }

    private fun observeOtaCoordinator() {
        viewModelScope.launch {
            updateRepository.coordinatorSnapshot.collect { snapshot ->
                _state.update {
                    it.copy(
                        otaPhase = snapshot.phase.name,
                        otaChannel = snapshot.offer?.channel?.name,
                        otaServerFeatureEnabled = snapshot.serverFeatureEnabled,
                        otaLegacyFallbackEnabled = snapshot.legacyFallbackEnabled,
                        otaMandatoryEnforcementEnabled = snapshot.mandatoryEnforcementEnabled,
                        availableUpdate = snapshot.offer?.toLegacyAppUpdate() ?: it.availableUpdate,
                        updateCheckError = snapshot.errorMessage,
                        isCheckingUpdate = snapshot.phase == com.viwa.android.domain.ota.AppUpdatePhase.Checking,
                        isInstalling =
                            snapshot.phase == com.viwa.android.domain.ota.AppUpdatePhase.Downloading ||
                                snapshot.phase == com.viwa.android.domain.ota.AppUpdatePhase.Verifying ||
                                snapshot.phase == com.viwa.android.domain.ota.AppUpdatePhase.Installing,
                        isUpToDate = snapshot.phase == com.viwa.android.domain.ota.AppUpdatePhase.Idle && snapshot.offer == null,
                    )
                }
            }
        }
    }

    private fun observeUpdateProgress() {
        viewModelScope.launch {
            try {
                updateRepository.progressFlow.collect { progress ->
                    _updateInstallProgress.value = progress
                }
            } catch (e: Exception) {
                Timber.e(e, "observeUpdateProgress failed")
            }
        }
    }

    private fun loadServiceState() {
        viewModelScope.launch {
            try {
                val mock = configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"
                val free = configRepository.get(JsonStoreKeys.DEV_FREE_MODE) != "false"
                val pulse =
                    PrimaryButtonPulseStyle.fromStorage(
                        configRepository.get(JsonStoreKeys.PRIMARY_BUTTON_PULSE_STYLE),
                    )
                val subDebug = configRepository.get(JsonStoreKeys.SUBSCRIPTION_DEBUG_MODE) == "true"
                _state.update {
                    it.copy(
                        useMockController = mock,
                        devFreeMode = free,
                        currentVersion = updateRepository.getCurrentVersion(),
                        currentVersionCode = updateRepository.getCurrentVersionCode(),
                        updateHost = updateRepository.getUpdateServerHost(),
                        otaLegacyFallbackEnabled = updateRepository.isLegacyFallbackEnabled(),
                        primaryButtonPulseStyle = pulse,
                        subscriptionDebugEnabled = subDebug,
                        flowStripRgbArgb = flowStripRgbCoordinator.getSavedArgb(),
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "loadServiceState failed")
            }
        }
    }

    fun setDarkTheme(dark: Boolean) {
        viewModelScope.launch { themeRepository.setIsDark(dark) }
    }

    fun previewCustomerPrimaryRgb(r: Int, g: Int, b: Int) {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        val argb = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        themeRepository.setCustomerPrimaryButtonArgbPreview(argb)
    }

    fun persistCustomerPrimaryButtonColor() {
        viewModelScope.launch { themeRepository.persistCustomerPrimaryButtonArgb() }
    }

    fun resetCustomerPrimaryButtonColor() {
        viewModelScope.launch { themeRepository.resetCustomerPrimaryButtonArgbToDefault() }
    }

    fun setDevFreeMode(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.set(
                JsonStoreKeys.DEV_FREE_MODE,
                if (enabled) "true" else "false",
            )
            _state.update { it.copy(devFreeMode = enabled) }
        }
    }

    fun setSubscriptionDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.set(
                JsonStoreKeys.SUBSCRIPTION_DEBUG_MODE,
                if (enabled) "true" else "false",
            )
            _state.update { it.copy(subscriptionDebugEnabled = enabled, subscriptionDebugSendResult = null) }
        }
    }

    fun sendSubscriptionDebugRequest(clientUuid: String) {
        val uuid = clientUuid.trim()
        if (uuid.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(subscriptionDebugSendBusy = true, subscriptionDebugSendResult = null) }
            val result = telemetryService.sendStatusSubscribeTopic(uuid)
            _state.update {
                it.copy(
                    subscriptionDebugSendBusy = false,
                    subscriptionDebugSendResult = if (result.isSuccess) "✓ Отправлено ($uuid)" else "✗ ${result.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun setPrimaryButtonPulseStyle(style: PrimaryButtonPulseStyle) {
        viewModelScope.launch {
            configRepository.set(JsonStoreKeys.PRIMARY_BUTTON_PULSE_STYLE, style.storageKey)
            _state.update { it.copy(primaryButtonPulseStyle = style) }
        }
    }

 /** UC-3: тестовая команда через шлюз + симуляция ответа в мок-режиме. */
    fun runControllerSelfTest() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    controllerTestRunning = true,
                    controllerTestBanner = null,
                    controllerTestIsError = false,
                )
            }
            try {
                val useMock = configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"
                controllerGateway.sendCommand(
                    RequestCommand.ReadFirmwareVersion,
                    byteArrayOf(0, 0, 0, 0, 0),
                )
                delay(150)
                if (useMock) {
                    controllerGateway.simulateResponseForTests(
                        ResponseCommand.ControllerVersionAnswer,
                        byteArrayOf(26, 4, 1),
                    )
                    _state.update {
                        it.copy(
                            controllerTestRunning = false,
                            controllerTestBanner =
                                "Тест OK (мок): отправлена ReadFirmwareVersion, симуляция ControllerVersionAnswer.",
                            controllerTestIsError = false,
                        )
                    }
                    Timber.tag("ViwaController").d("UC-3 self-test finished (mock: send + simulate)")
                } else {
                    _state.update {
                        it.copy(
                            controllerTestRunning = false,
                            controllerTestBanner =
                                "Тест OK (режим «реал»): кадр FE… ушёл в serial-транспорт (заглушка); RX с порта нет.",
                            controllerTestIsError = false,
                        )
                    }
                    Timber.tag("ViwaController").d("UC-3 self-test finished (stub real, no simulate)")
                }
            } catch (e: Exception) {
                Timber.e(e, "runControllerSelfTest failed")
                _state.update {
                    it.copy(
                        controllerTestRunning = false,
                        controllerTestBanner = e.message ?: e.javaClass.simpleName,
                        controllerTestIsError = true,
                    )
                }
            }
        }
    }

 /** B4: демонстрация цепочки «терминал → 0x48 → контроллер». */
    fun runPaymentTerminal048Demo() {
        viewModelScope.launch {
            try {
                controllerSbpNotifyService.notifySbpPayment(
                    TerminalProductType.Drink,
                    price = 100,
                    productNumber = 1,
                )
                _state.update {
                    it.copy(
                        paymentTerminalTestBanner =
                            "SendSumToPaymentTerminal (0x48, СБП) отправлен — см. logcat ViwaController / SbpNotify",
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "runPaymentTerminal048Demo failed")
                _state.update {
                    it.copy(paymentTerminalTestBanner = e.message ?: "Ошибка 0x48")
                }
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateInstallProgress.value = null
            _state.update {
                it.copy(
                    isCheckingUpdate = true,
                    availableUpdate = null,
                    updateCheckError = null,
                    isUpToDate = false,
                )
            }
            val result =
                if (_state.value.otaLegacyFallbackEnabled) {
                    updateRepository.checkUpdate()
                } else {
                    updateRepository.checkTelemetryUpdate()
                }
            result.fold(
                onSuccess = { update ->
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            availableUpdate = update,
                            isUpToDate = update == null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateCheckError = e.message ?: "Ошибка проверки обновлений",
                        )
                    }
                },
            )
        }
    }

    fun installUpdate(update: AppUpdate) {
        viewModelScope.launch {
            _updateInstallProgress.value = null
            _state.update { it.copy(isInstalling = true, updateCheckError = null) }
            val hasFirmwareScope =
                technicianServiceMenuAccess.hasScope(
                    com.viwa.android.domain.technician.TechnicianKeyConstants.SCOPE_FIRMWARE_UPDATE,
                )
            val result =
                if (update.telemetryOffer) {
                    updateRepository.installTelemetryUpdate(
                        requireFirmwareScope = true,
                        hasFirmwareScope = hasFirmwareScope,
                    )
                } else {
                    updateRepository.downloadAndInstall(update)
                }
            result.fold(
                onSuccess = {
                    _updateInstallProgress.value = null
                    _state.update { it.copy(isInstalling = false) }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isInstalling = false,
                            updateCheckError = e.message ?: "Ошибка установки обновления",
                        )
                    }
                },
            )
        }
    }

    fun setLegacyOtaFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            updateRepository.setLegacyFallbackEnabled(enabled)
            _state.update { it.copy(otaLegacyFallbackEnabled = enabled) }
        }
    }

    fun setUpdateHost(host: String) {
        viewModelScope.launch {
            updateRepository.setUpdateServerHost(host)
            _state.update { it.copy(updateHost = host) }
        }
    }

    fun refreshWaterCalibrationInfo() {
        viewModelScope.launch {
            runCatching {
                val info = waterCalibrationService.loadCalibration()
                val windowSize = waterCalibrationService.loadAdaptiveWindowSize()
                _state.update {
                    it.copy(
                        waterCalInfo = info,
                        waterCalAdaptiveWindowInput = windowSize.toString(),
                    )
                }
            }.onFailure { Timber.e(it, "refreshWaterCalibrationInfo") }
        }
    }

    fun refreshPreparingStatsData() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    preparingStatsBusy = true,
                    preparingStatsBanner = null,
                    preparingStatsBannerIsError = false,
                )
            }
            runCatching {
                val snapshot = telemetryCellsRepository.getSnapshot()
                val containers =
                    snapshot?.let { TelemetryCellsSnapshotAdapter.toDrinkContainers(it) }.orEmpty()
                val flowRate = waterCalibrationService.loadCalibration().flowRateMlPerSec
                val history = preparingTimeHistoryStore.loadAll()
                val options = containers.map { it.toPreparingStatsOption() }
                val selectedTasteId =
                    when {
                        options.isEmpty() -> null
                        options.any { it.tasteId == _state.value.preparingStatsSelectedTasteId } ->
                            _state.value.preparingStatsSelectedTasteId
                        else -> options.first().tasteId
                    }
                val historyRows = mapPreparingHistoryRows(history, selectedTasteId)
                val rawExit = configRepository.get(JsonStoreKeys.PREPARING_AUTO_EXIT_MINUTES)
                val exitInput =
                    when (rawExit) {
                        null -> "5"
                        else ->
                            rawExit.toIntOrNull()?.coerceIn(0, 240)?.toString()
                                ?: "5"
                    }
                _state.update {
                    it.copy(
                        preparingStatsBusy = false,
                        preparingStatsDrinks = options,
                        preparingStatsSelectedTasteId = selectedTasteId,
                        preparingStatsFlowRateMlPerSec = flowRate,
                        preparingStatsHistory = historyRows,
                        preparingStatsBanner =
                            if (options.isEmpty()) "Нет напитков в merge-конфиге телеметрии" else null,
                        preparingStatsBannerIsError = false,
                        preparingAutoExitMinutesInput = exitInput,
                        preparingAutoExitSaveBanner = null,
                        preparingAutoExitSaveBannerIsError = false,
                    )
                }
            }.onFailure { e ->
                Timber.e(e, "refreshPreparingStatsData")
                _state.update {
                    it.copy(
                        preparingStatsBusy = false,
                        preparingStatsBanner = e.message ?: "Не удалось загрузить данные времени готовки",
                        preparingStatsBannerIsError = true,
                    )
                }
            }
        }
    }

    fun setPreparingStatsSelectedTasteId(tasteId: Int) {
        val selected = _state.value.preparingStatsDrinks.firstOrNull { it.tasteId == tasteId } ?: return
        viewModelScope.launch {
            val history = preparingTimeHistoryStore.loadAll()
            _state.update {
                it.copy(
                    preparingStatsSelectedTasteId = selected.tasteId,
                    preparingStatsHistory = mapPreparingHistoryRows(history, selected.tasteId),
                )
            }
        }
    }

    fun setPreparingAutoExitMinutesInput(value: String) {
        _state.update {
            it.copy(
                preparingAutoExitMinutesInput = value.filter { ch -> ch.isDigit() }.take(3),
                preparingAutoExitSaveBanner = null,
                preparingAutoExitSaveBannerIsError = false,
            )
        }
    }

    fun savePreparingAutoExitMinutes() {
        viewModelScope.launch {
            val input = _state.value.preparingAutoExitMinutesInput.trim()
            val v = input.toIntOrNull()
            if (v == null || v !in 0..240) {
                _state.update {
                    it.copy(
                        preparingAutoExitSaveBanner = "Введите число от 0 до 240 (0 — без автовыхода)",
                        preparingAutoExitSaveBannerIsError = true,
                    )
                }
                return@launch
            }
            runCatching {
                configRepository.set(JsonStoreKeys.PREPARING_AUTO_EXIT_MINUTES, v.toString())
            }
                .onSuccess {
                    _state.update {
                        it.copy(
                            preparingAutoExitMinutesInput = v.toString(),
                            preparingAutoExitSaveBanner = "Сохранено",
                            preparingAutoExitSaveBannerIsError = false,
                        )
                    }
                }
                .onFailure { e ->
                    Timber.e(e, "savePreparingAutoExitMinutes")
                    _state.update {
                        it.copy(
                            preparingAutoExitSaveBanner = e.message ?: "Не удалось сохранить",
                            preparingAutoExitSaveBannerIsError = true,
                        )
                    }
                }
        }
    }

    private fun mapPreparingHistoryRows(
        allRecords: List<PreparingTimeRecord>,
        selectedTasteId: Int?,
    ): List<PreparingStatsHistoryRow> {
        if (selectedTasteId == null) return emptyList()
        return allRecords
            .asReversed()
            .asSequence()
            .filter { it.tasteId == selectedTasteId }
            .take(10)
            .map { rec ->
                val deltaSec = rec.actualTimeSec - rec.expectedTimeSec.toDouble()
                val deltaPercent =
                    if (rec.expectedTimeSec > 0) {
                        (deltaSec / rec.expectedTimeSec.toDouble()) * 100.0
                    } else {
                        0.0
                    }
                PreparingStatsHistoryRow(
                    timestampEpochMs = rec.timestampEpochMs,
                    volumeMl = rec.volumeMl,
                    expectedTimeSec = rec.expectedTimeSec,
                    actualTimeSec = rec.actualTimeSec,
                    deltaSec = deltaSec,
                    deltaPercent = deltaPercent,
                )
            }.toList()
    }

    private fun DrinkContainer.toPreparingStatsOption(): PreparingStatsDrinkOption =
        PreparingStatsDrinkOption(
            tasteId = product.taste.id,
            title =
                buildString {
                    append("#")
                    append(containerNumber)
                    append(" ")
                    append(product.name)
                    if (product.taste.name.isNotBlank()) {
                        append(" · ")
                        append(product.taste.name)
                    }
                },
            recipeDrinkVolumeMl = product.dosage.drinkVolume,
            recipeWaterMl = product.dosage.water,
        )

    fun setWaterCalTargetMlInput(value: String) {
        _state.update { it.copy(waterCalTargetMlInput = value.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun setWaterCalActualMlInput(value: String) {
        _state.update { it.copy(waterCalActualMlInput = value.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun setWaterCalAdaptiveWindowInput(value: String) {
        _state.update { it.copy(waterCalAdaptiveWindowInput = value.filter { ch -> ch.isDigit() }.take(2)) }
    }

    fun startWaterCalibrationPour() {
        viewModelScope.launch {
            val ml = _state.value.waterCalTargetMlInput.toIntOrNull() ?: 0
            if (ml <= 0) {
                _state.update {
                    it.copy(
                        waterCalBanner = "Введите целевой объём (мл) больше 0",
                        waterCalBannerIsError = true,
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    waterCalPourBusy = true,
                    waterCalPourResult = null,
                    waterCalBanner = null,
                )
            }
            runCatching {
                when (val r = waterCalibrationService.runTestPour(ml)) {
                    is WaterPourResult.Success ->
                        _state.update {
                            it.copy(
                                waterCalPourBusy = false,
                                waterCalPourResult =
                                    "Налив завершён: ${"%.2f".format(r.durationSec)} с (цель $ml мл)",
                                waterCalInfo = waterCalibrationService.loadCalibration(),
                                waterCalBanner = null,
                            )
                        }
                    is WaterPourResult.Failure ->
                        _state.update {
                            it.copy(
                                waterCalPourBusy = false,
                                waterCalPourResult = r.message,
                                waterCalInfo = waterCalibrationService.loadCalibration(),
                                waterCalBanner = null,
                            )
                        }
                }
            }.onFailure { e ->
                Timber.e(e, "startWaterCalibrationPour")
                _state.update {
                    it.copy(
                        waterCalPourBusy = false,
                        waterCalPourResult = e.message ?: "Ошибка налива",
                    )
                }
            }
        }
    }

    fun saveWaterCalibrationCoefficient() {
        viewModelScope.launch {
            val target = _state.value.waterCalTargetMlInput.toIntOrNull() ?: 0
            val actual = _state.value.waterCalActualMlInput.toIntOrNull() ?: 0
            _state.update {
                it.copy(waterCalSaveBusy = true, waterCalBanner = null)
            }
            runCatching {
                when (val r = waterCalibrationService.writeCoefficient(targetVolumeMl = target, actualVolumeMl = actual)) {
                    is WaterCalibrationWriteResult.Success -> {
                        val rate = r.data.flowRateMlPerSec
                        val rateStr = rate?.let { "%.2f".format(it) } ?: "—"
                        _state.update {
                            it.copy(
                                waterCalSaveBusy = false,
                                waterCalInfo = r.data,
                                waterCalBanner = "Калибровка сохранена. Расход: $rateStr мл/с",
                                waterCalBannerIsError = false,
                            )
                        }
                    }
                    is WaterCalibrationWriteResult.Failure ->
                        _state.update {
                            it.copy(
                                waterCalSaveBusy = false,
                                waterCalBanner = r.message,
                                waterCalBannerIsError = true,
                            )
                        }
                }
            }.onFailure { e ->
                Timber.e(e, "saveWaterCalibrationCoefficient")
                _state.update {
                    it.copy(
                        waterCalSaveBusy = false,
                        waterCalBanner = e.message ?: "Ошибка записи",
                        waterCalBannerIsError = true,
                    )
                }
            }
        }
    }

    fun recomputeWaterFlowRateFromHistory() {
        viewModelScope.launch {
            val requestedWindow = _state.value.waterCalAdaptiveWindowInput.toIntOrNull() ?: 0
            if (requestedWindow <= 0) {
                _state.update {
                    it.copy(
                        waterCalBanner = "Введите число последних наливов (1..20)",
                        waterCalBannerIsError = true,
                    )
                }
                return@launch
            }

            _state.update { it.copy(waterCalRecomputeBusy = true, waterCalBanner = null) }
            runCatching {
                val normalizedWindow =
                    requestedWindow.coerceIn(
                        com.viwa.android.services.calibration.WaterCalibrationCalculations.MIN_ADAPTIVE_WINDOW_SIZE,
                        com.viwa.android.services.calibration.WaterCalibrationCalculations.MAX_ADAPTIVE_WINDOW_SIZE,
                    )
                val updated = waterCalibrationService.recomputeFlowRateFromHistory(normalizedWindow)
                _state.update {
                    if (updated == null) {
                        it.copy(
                            waterCalRecomputeBusy = false,
                            waterCalAdaptiveWindowInput = normalizedWindow.toString(),
                            waterCalBanner = "Недостаточно данных готовок для пересчёта",
                            waterCalBannerIsError = true,
                        )
                    } else {
                        it.copy(
                            waterCalRecomputeBusy = false,
                            waterCalInfo = updated,
                            waterCalAdaptiveWindowInput = normalizedWindow.toString(),
                            waterCalBanner =
                                "Скорость пересчитана по последним $normalizedWindow наливам: ${"%.2f".format(updated.flowRateMlPerSec)} мл/с",
                            waterCalBannerIsError = false,
                        )
                    }
                }
            }.onFailure { e ->
                Timber.e(e, "recomputeWaterFlowRateFromHistory")
                _state.update {
                    it.copy(
                        waterCalRecomputeBusy = false,
                        waterCalBanner = e.message ?: "Ошибка пересчёта скорости",
                        waterCalBannerIsError = true,
                    )
                }
            }
        }
    }
}
