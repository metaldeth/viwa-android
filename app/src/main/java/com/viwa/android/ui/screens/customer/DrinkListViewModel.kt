package com.viwa.android.ui.screens.customer

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.hardware.controller.ControllerTrafficEntry
import com.viwa.android.hardware.controller.ViwaControllerTrafficLogger
import com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapter
import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.model.PaymentMethod
import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.model.customer.PrimaryButtonPulseStyle
import com.viwa.android.domain.model.customer.isUnavailable
import com.viwa.android.domain.model.customer.toFlowWaterPourType
import com.viwa.android.domain.model.customer.toDrinkWaterOption
import com.viwa.android.domain.model.customer.WaterPourByTouchPayload
import com.viwa.android.domain.model.customer.toRatio
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.usecase.CheckSBPStatusUseCase
import com.viwa.android.domain.usecase.GetSBPLinkUseCase
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.hardware.controller.decodeFlowTemperatureByte
import com.viwa.android.hardware.controller.RequestCommand
import com.viwa.android.hardware.controller.ResponseCommand
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import com.viwa.android.domain.telemetry.LoyaltyPlainWaterPreference
import com.viwa.android.domain.telemetry.PlainWaterEntitlement
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.offline.SubscriptionPourContext
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PrepareDrinkResult
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.payment.TerminalProductType
import com.viwa.android.services.telemetry.ViwaTelemetryService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.viwa.android.BuildConfig
import java.text.SimpleDateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

enum class PaymentSheetStep {
    MethodChoice,
    /** Параллельная карта + СБП для напитка (без выбора способа). */
    Combined,
    Card,
    Sbp,
}

/** Объём по умолчанию при первом выборе напитка (шапка: 300 / 700 мл). Раньше было 700 мл. */
private const val DEFAULT_SELECTED_VOLUME_ML = 300

@Immutable
data class DrinkListUiState(
 /** Пока нет `TELEMETRY_MERGED_INVENTORY` — пусто; реальное наполнение приходит из телеметрии. */
    val containers: List<DrinkContainer> = emptyList(),
    val activeContainer: DrinkContainer? = null,
    val selectedVolumeMl: Int? = null,
    val waterOption: DrinkWaterOption = DrinkWaterOption.STANDARD,
    val concentration: DrinkConcentration = DrinkConcentration.Standard,
    val freeMode: Boolean = true,
    val useMockController: Boolean = true,
 /** `false` — мок выключен и физический контроллер не подключён; блокирует покупки. */
    val controllerAvailable: Boolean = true,
    val flowBanner: String? = null,
    val flowBannerIsError: Boolean = false,
    val isProcessingPay: Boolean = false,
 /** Как `PaymentModal` в `DrinkListPage.tsx`: сначала выбор способа, затем сценарий. */
    val paymentSheetVisible: Boolean = false,
    val paymentSheetStep: PaymentSheetStep = PaymentSheetStep.MethodChoice,
    val paymentTerminalBanner: String = "",
    val cardPaymentUiStatus: CardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
 /** true после claim combined-оплаты: блокирует cancel/dismiss и повторный pay до явного сброса экрана. */
    val combinedPaymentConfirmed: Boolean = false,
    val paymentError: String? = null,
    val scannedSubscriptionClientId: String? = null,
    val isSubscriptionActive: Boolean = false,
    val subscriptionVolumeMl: Int = 0,
    val subscriptionMaxVolumeMl: Int = 0,
    val subscriptionEndDate: String? = null,
    val invalidSubscriptionCardVisible: Boolean = false,
    val sbpLink: SBPLink? = null,
    val sbpStatus: SBPStatus = SBPStatus.Pending,
    val sbpRemainingSeconds: Int = 0,
    val isSbpLoading: Boolean = false,
 /** Как `useTelemetryConnection` / `WS_CONNECTION_STATUS`. */
    val telemetryWsConnected: Boolean = false,
 /** Температура датчика T0 (°C), null до первого опроса. */
    val temperature0C: Int? = null,
 /** Температура датчика T1 (°C), null до первого опроса. */
    val temperature1C: Int? = null,
 /** Накопленный расход воды (мл), [JsonStoreKeys.WATER_USAGE_ML]; бутылка = 0,5 л → число бутылок = мл / 500. */
    val accumulatedWaterMl: Double = 0.0,
 /** Анимация основной кнопки (сервис → Производительность → Анимации). */
    val primaryButtonPulseStyle: PrimaryButtonPulseStyle = PrimaryButtonPulseStyle.PulseScale,
 /** Удержание кнопки «Налить воду»: после дебаунса идёт команда D0 на контроллер. */
    val isWaterPourActive: Boolean = false,
 /** Лимит 30 с удержания (. */
    val waterPourLimitBanner: Boolean = false,
    val waterPourError: String? = null,
 /** Тип воды для D0 при отсканированной карте (нижний ряд); без карты — не используется в команде. */
    val flowWaterPourType: FlowWaterPourType = FlowWaterPourType.Filtered,
    /** Показывать FAB отладки подписки (Сервис → Дебаг → Подписка → «Режим отладки»). */
    val subscriptionDebugEnabled: Boolean = false,
    /** Обратный отсчёт автовыхода из подписки при отсутствии касаний (сек). */
    val subscriptionExitRemainingSeconds: Int = 0,
)

/** Full-screen or blocking customer flows on Home — idle overlay must stay hidden. */
fun DrinkListUiState.blocksIdleVideoOverlay(): Boolean =
    paymentSheetVisible ||
        invalidSubscriptionCardVisible ||
        isProcessingPay ||
        isWaterPourActive

@HiltViewModel
class DrinkListViewModel
@Inject
constructor(
    private val configRepository: ConfigRepository,
    private val telemetryCellsRepository: TelemetryCellsRepository,
    private val preparingManager: PreparingManager,
    private val controllerGateway: ControllerGateway,
    private val flowTemperatureStore: FlowTemperatureStore,
    private val aqsiUsbPaymentManager: AqsiUsbPaymentManager,
    private val controllerSbpNotifyService: ControllerSbpNotifyService,
    private val telemetryService: ViwaTelemetryService,
    private val getSBPLinkUseCase: GetSBPLinkUseCase,
    private val checkSBPStatusUseCase: CheckSBPStatusUseCase,
    private val sbpRepository: SBPRepository,
    private val nanoKassaRepository: NanoKassaRepository,
    private val networkTrafficLogger: NetworkTrafficLogger,
    private val controllerTrafficLogger: ViwaControllerTrafficLogger,
    private val cardPaymentOrchestrator: CardPaymentOrchestrator,
    private val holdPourTelemetryCoordinator: HoldPourTelemetryCoordinator,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(DrinkListUiState())
    val state: StateFlow<DrinkListUiState> = _state.asStateFlow()

    val networkTrafficFlow: StateFlow<List<NetworkTrafficEntry>> = networkTrafficLogger.entries
    val controllerTrafficFlow: StateFlow<List<ControllerTrafficEntry>> = controllerTrafficLogger.entries

    private var paymentJob: Job? = null
    private var sbpPollingJob: Job? = null
    private var sbpTimerJob: Job? = null
    private var sbpRetryJob: Job? = null
    private var combinedPaymentTimerJob: Job? = null
    private var subscriptionExitTimerJob: Job? = null
    private val combinedPaymentSettled = AtomicBoolean(false)
    private val combinedPaymentSessionLock = Any()
    private var combinedPaymentClosed = false
    private var combinedCardFailed = false
    private var combinedSbpFailed = false
    private var combinedSbpErrorMessage: String? = null

    private fun abandonPaymentJobSlot() {
        if (combinedPaymentSettled.get()) return
        val j = paymentJob
        paymentJob = null
        viewModelScope.launch {
            cardPaymentOrchestrator.cancelActivePayment()
            j?.cancel()
        }
    }

 /** Перед новым платёжным Job: закрыть предыдущую карточную сессию и дождаться отмены Job. */
    private suspend fun finishPaymentJobForReplacement(previous: Job?) {
        cardPaymentOrchestrator.cancelActivePayment()
        previous?.cancelAndJoin()
    }

    private var waterPourDebounceJob: Job? = null
    private var waterPourMaxHoldJob: Job? = null
    private var waterPourLimitHideJob: Job? = null
    private var waterPourStarted: Boolean = false
    private var subscriptionExitPausedForPour: Boolean = false
    private var holdPourRequestUuid: String? = null
    private var pendingSubscriptionPourRequestUuid: String? = null
    private val paymentClearingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

 /** Последние значения T0/T1, отправленные в телеметрию — для детектирования изменений. */
    private var lastSentT0: Int? = null
    private var lastSentT1: Int? = null
    init {
        refreshFlags()
        viewModelScope.launch {
            controllerGateway.isPhysicalControllerConnected.collect { physicalConnected ->
                val mock = configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"
                _state.update { it.copy(controllerAvailable = mock || physicalConnected) }
            }
        }
        viewModelScope.launch {
            aqsiUsbPaymentManager.terminalStatusFlow.collect { text ->
                _state.update { st ->
                    val cardStatus =
                        if (st.paymentSheetStep == PaymentSheetStep.Combined) {
                            resolveCombinedCardPaymentUiStatus(
                                terminalBanner = text,
                                current = st.cardPaymentUiStatus,
                                paymentConfirmed = st.combinedPaymentConfirmed,
                            )
                        } else {
                            st.cardPaymentUiStatus
                        }
                    st.copy(paymentTerminalBanner = text, cardPaymentUiStatus = cardStatus)
                }
            }
        }
        viewModelScope.launch {
            telemetryCellsRepository.snapshotFlow.collect { snapshot ->
                val live =
                    snapshot?.let { TelemetryCellsSnapshotAdapter.toDrinkContainers(it) }.orEmpty()
                applyInventoryContainers(live)
            }
        }
        viewModelScope.launch {
            var wasConnected = false
            telemetryService.connectionState.collect { cs ->
                val connected = cs is ConnectionState.Connected
                if (connected && !wasConnected) {
                    lastSentT0 = null
                    lastSentT1 = null
                }
                wasConnected = connected
                _state.update { it.copy(telemetryWsConnected = connected) }
            }
        }
        viewModelScope.launch {
            telemetryService.subscribeInfo.collect { info ->
                if (info == null) {
                    val pourSessionActive =
                        waterPourStarted || holdPourRequestUuid != null || _state.value.isWaterPourActive
                    stopSubscriptionExitTimer()
                    _state.update { st ->
                        applyPlainWaterPreferenceIfNoDrink(
                            st.copy(
                                scannedSubscriptionClientId = null,
                                isSubscriptionActive = false,
                                subscriptionVolumeMl = 0,
                                subscriptionMaxVolumeMl = 0,
                                subscriptionEndDate = null,
                                invalidSubscriptionCardVisible = false,
                                subscriptionExitRemainingSeconds = 0,
                            ),
                            preferredPourType = FlowWaterPourType.Filtered,
                        )
                    }
                    reactToSubscriptionEntitlementLoss(pourSessionActive)
                    return@collect
                }
                val previousClientId = _state.value.scannedSubscriptionClientId
                val subscriptionActive = info.isActiveSubscribe
                val pourSessionActive =
                    waterPourStarted || holdPourRequestUuid != null || _state.value.isWaterPourActive
                val localPrefsJson = configRepository.getJson(JsonStoreKeys.LOYALTY_CLIENT_PLAIN_WATER_PREFS)
                val preferredPourType =
                    LoyaltyPlainWaterPreference.resolvePourType(
                        clientId = info.clientId,
                        serverPlainWaterType = info.lastPlainWaterType,
                        localPrefsJson = localPrefsJson,
                        subscriptionActive = subscriptionActive,
                        coerceEntitlement = true,
                    )
                _state.update {
                    applyPlainWaterPreferenceIfNoDrink(
                        it.copy(
                            scannedSubscriptionClientId = info.clientId,
                            isSubscriptionActive = subscriptionActive,
                            subscriptionVolumeMl = info.volumeMl,
                            subscriptionMaxVolumeMl = info.maxVolumeMl,
                            subscriptionEndDate = info.subscribeDateEnd,
                            invalidSubscriptionCardVisible = false,
                        ),
                        preferredPourType = preferredPourType,
                    )
                }
                if (previousClientId != info.clientId) {
                    startSubscriptionExitTimer()
                }
                if (!subscriptionActive) {
                    reactToSubscriptionEntitlementLoss(pourSessionActive)
                }
            }
        }
        observeLoyaltyCardScansForDrinkListUi()
        observeInvalidLoyaltyCards()
        observeOfflineLoyaltyDeny()
        observeControllerTemperature()
        startTemperaturePolling()
    }

 /**
 * Реакция UI на глобальный скан карты: [ViwaTelemetryService.loyaltyCardClientScans]
 * (запросы в WS шлёт [LoyaltyCardScanCoordinator], как onLoyaltyCardArrived.
 */
    private fun observeLoyaltyCardScansForDrinkListUi() {
        viewModelScope.launch {
            telemetryService.loyaltyCardClientScans.collect { clientId ->
                resetSubscriptionExitTimer()
                val localPrefsJson = configRepository.getJson(JsonStoreKeys.LOYALTY_CLIENT_PLAIN_WATER_PREFS)
                val pourType =
                    LoyaltyPlainWaterPreference.resolvePourType(
                        clientId = clientId,
                        serverPlainWaterType = null,
                        localPrefsJson = localPrefsJson,
                        subscriptionActive = _state.value.isSubscriptionActive,
                        coerceEntitlement = false,
                    )
                _state.update {
                    applyPlainWaterPreferenceIfNoDrink(
                        it.copy(invalidSubscriptionCardVisible = false),
                        preferredPourType = pourType,
                    )
                }
            }
        }
    }

    private fun observeOfflineLoyaltyDeny() {
        viewModelScope.launch {
            telemetryService.offlineLoyaltyDenyReason.collect { reason ->
                _state.update {
                    it.copy(
                        flowBanner = offlineSubscriptionDenyMessage(reason),
                        flowBannerIsError = true,
                        invalidSubscriptionCardVisible = reason != OfflineAuthorizationReason.GRANTED,
                    )
                }
            }
        }
    }

    private fun offlineSubscriptionDenyMessage(reason: OfflineAuthorizationReason): String =
        when (reason) {
            OfflineAuthorizationReason.OFFLINE_NO_GRANT -> "Нет offline-разрешения. Подключите сеть."
            OfflineAuthorizationReason.OFFLINE_GRANT_EXPIRED -> "Offline-разрешение истекло."
            OfflineAuthorizationReason.OFFLINE_GRANT_REVOKED -> "Offline-разрешение отозвано."
            OfflineAuthorizationReason.OFFLINE_GRANT_TAMPERED -> "Offline-разрешение недействительно."
            OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE -> "Ненадёжные часы. Подключите сеть."
            OfflineAuthorizationReason.OFFLINE_POUR_LIMIT -> "Лимит offline-наливов исчерпан."
            OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT -> "Превышен offline-объём."
            OfflineAuthorizationReason.OFFLINE_DAILY_EXCEEDED -> "Превышен дневной лимит."
            OfflineAuthorizationReason.OFFLINE_DISABLED -> "Offline-налив недоступен для тарифа."
            OfflineAuthorizationReason.OFFLINE_FEATURE_DISABLED -> "Offline-налив отключён."
            OfflineAuthorizationReason.OFFLINE_GRANT_MACHINE_MISMATCH -> "Grant не для этой машины."
            OfflineAuthorizationReason.GRANTED -> "OK"
        }

    private fun observeInvalidLoyaltyCards() {
        viewModelScope.launch {
            telemetryService.invalidLoyaltyCardScans.collect {
                _state.update {
                    it.copy(invalidSubscriptionCardVisible = true)
                }
            }
        }
    }

 /**
 * Подписывается на ответы контроллера и обновляет температуру в state.
 * Отправляет setMachineInfo в телеметрию только при изменении значений.
 */
    private fun observeControllerTemperature() {
        viewModelScope.launch {
            controllerGateway.incomingResponses.collect { ev ->
                if (ev.response == ResponseCommand.ControllerTimeoutResetActivate && ev.payload.size >= 2) {
                    val t0 = decodeFlowTemperatureByte(ev.payload[0].toInt() and 0xff)
                    val t1 = decodeFlowTemperatureByte(ev.payload[1].toInt() and 0xff)
                    _state.update { it.copy(temperature0C = t0, temperature1C = t1) }
                    flowTemperatureStore.update(t0, t1)
                    if (t0 != lastSentT0 || t1 != lastSentT1) {
                        lastSentT0 = t0
                        lastSentT1 = t1
                    }
                }
            }
        }
    }

 /**
 * Периодический опрос температуры раз в 10 секунд.
 * Запрос отправляется только в режиме ожидания: не во время оплаты и не во время готовки/выдачи напитка.
 */
    private fun startTemperaturePolling() {
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val s = _state.value
                val preparing = preparingManager.customerPhase.value !is CustomerPreparingPhase.Idle
                if (!s.isProcessingPay && !s.paymentSheetVisible && !preparing) {
                    runCatching {
                        controllerGateway.sendCommand(
                            RequestCommand.ReadFlowTemperature,
                            byteArrayOf(0, 0, 0, 0, 0),
                        )
                    }.onFailure { Timber.w(it, "ReadFlowTemperature polling failed") }
                }
            }
        }
    }

    private fun applyInventoryContainers(live: List<DrinkContainer>) {
        if (live.isEmpty()) return
        val active = _state.value.activeContainer
        val updatedActive =
            active?.let { a ->
                live.firstOrNull { it.containerNumber == a.containerNumber }
            }
        _state.update { it.copy(containers = live, activeContainer = updatedActive) }
    }

    fun refreshFlags() {
        viewModelScope.launch {
            val mock = configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"
            val free = configRepository.get(JsonStoreKeys.DEV_FREE_MODE) != "false"
            val physicalConnected = controllerGateway.isPhysicalControllerConnected.value
            val pulse =
                PrimaryButtonPulseStyle.fromStorage(
                    configRepository.get(JsonStoreKeys.PRIMARY_BUTTON_PULSE_STYLE),
                )
            val subDebug = configRepository.get(JsonStoreKeys.SUBSCRIPTION_DEBUG_MODE) == "true"
            val waterMl = configRepository.get(JsonStoreKeys.WATER_USAGE_ML)?.toDoubleOrNull() ?: 0.0
            _state.update {
                it.copy(
                    useMockController = mock,
                    freeMode = free,
                    controllerAvailable = mock || physicalConnected,
                    primaryButtonPulseStyle = pulse,
                    subscriptionDebugEnabled = subDebug,
                    accumulatedWaterMl = waterMl,
                )
            }
        }
    }

 /** Как скан `CLIENT_<uuid>`: statusSubscribeTopic + subscriptionLevelTopic. */
    fun emulateSubscriptionQrScan(clientUuid: String = EMULATED_SUBSCRIPTION_CLIENT_UUID) {
        telemetryService.onLoyaltyCardScanned(clientUuid)
    }

    fun selectContainer(container: DrinkContainer) {
        if (container.isUnavailable()) return
        _state.update {
            val vol =
                if (it.selectedVolumeMl != null) {
                    it.selectedVolumeMl
                } else {
                    val offered = container.product.dPrices.map { p -> p.volume }.toSet()
                    when {
                        DEFAULT_SELECTED_VOLUME_ML in offered -> DEFAULT_SELECTED_VOLUME_ML
                        700 in offered -> 700
                        300 in offered -> 300
                        else -> container.product.dPrices.minOfOrNull { p -> p.volume } ?: DEFAULT_SELECTED_VOLUME_ML
                    }
                }
            it.copy(
                activeContainer = container,
                selectedVolumeMl = vol,
                flowBanner = null,
            )
        }
    }

    fun selectContainerByNumber(containerNumber: Int) {
        val container = _state.value.containers.firstOrNull { it.containerNumber == containerNumber } ?: return
        selectContainer(container)
    }

    /**
     * Предпочтение простой воды (карта/подписка) действует только без выбранного напитка.
     * Если напиток уже выбран — не сбрасывать его тип воды на стандарт/кэш клиента.
     */
    private fun applyPlainWaterPreferenceIfNoDrink(
        state: DrinkListUiState,
        preferredPourType: FlowWaterPourType,
    ): DrinkListUiState {
        if (state.activeContainer != null) return state
        return state.copy(
            waterOption = preferredPourType.toDrinkWaterOption(),
            flowWaterPourType = preferredPourType,
        )
    }

    fun setVolume(ml: Int) {
        _state.update { it.copy(selectedVolumeMl = ml) }
    }

    fun setWater(option: DrinkWaterOption) {
        val s = _state.value
        // Для выбранного напитка cold/spark — опция рецепта (toTofByte), без gate подписки.
        // Для hold-to-pour только по карте — premium требует активную подписку.
        val resolved =
            if (s.activeContainer != null) {
                option
            } else {
                PlainWaterEntitlement.coerceWaterOption(option, s.isSubscriptionActive)
            }
        _state.update {
            it.copy(
                waterOption = resolved,
                flowWaterPourType = resolved.toFlowWaterPourType(),
            )
        }
        s.scannedSubscriptionClientId?.let { clientId ->
            viewModelScope.launch {
                val encoded =
                    LoyaltyPlainWaterPreference.encodeLocalPrefs(
                        configRepository.getJson(JsonStoreKeys.LOYALTY_CLIENT_PLAIN_WATER_PREFS),
                        clientId,
                        resolved,
                    )
                configRepository.setJson(JsonStoreKeys.LOYALTY_CLIENT_PLAIN_WATER_PREFS, encoded)
            }
        }
    }

    fun setConcentration(c: DrinkConcentration) {
        _state.update { it.copy(concentration = c) }
    }

    fun clearWaterPourError() {
        _state.update { it.copy(waterPourError = null) }
    }

    fun setFlowWaterPourType(type: FlowWaterPourType) {
        val coerced = PlainWaterEntitlement.effectivePourType(type, _state.value.isSubscriptionActive)
        _state.update {
            it.copy(
                flowWaterPourType = coerced,
                waterOption = coerced.toDrinkWaterOption(),
            )
        }
    }

 /**
 * Налив воды по удержанию:
 * дебаунс [WATER_POUR_DEBOUNCE_MS], старт D0 (twf/SelW по состоянию), стоп [0…0], лимит [WATER_POUR_MAX_MS].
 */
    fun waterPourPointerDown() {
        pauseSubscriptionExitTimerForWaterPour()
        waterPourDebounceJob?.cancel()
        waterPourDebounceJob =
            viewModelScope.launch {
                delay(WATER_POUR_DEBOUNCE_MS)
                if (!isActive) return@launch
                waterPourStarted = true
                _state.update { it.copy(isWaterPourActive = true, waterPourError = null) }
                runCatching {
                    controllerGateway.sendCommand(
                        RequestCommand.WaterPourByTouch,
                        waterPourStartPayload(),
                    )
                }.onFailure { e ->
                    Timber.e(e, "waterPour start")
                    waterPourStarted = false
                    _state.update {
                        it.copy(
                            isWaterPourActive = false,
                            waterPourError = e.message ?: "Ошибка старта налива воды",
                        )
                    }
                    resumeSubscriptionExitTimerAfterWaterPour()
                    return@launch
                }
                beginHoldPourTelemetryIfNeeded()
                waterPourMaxHoldJob?.cancel()
                waterPourMaxHoldJob =
                    viewModelScope.launch {
                        delay(WATER_POUR_MAX_MS)
                        if (!waterPourStarted) return@launch
                        runCatching {
                            controllerGateway.sendCommand(
                                RequestCommand.WaterPourByTouch,
                                WaterPourByTouchPayload.stopBody,
                            )
                        }.onFailure { Timber.w(it, "waterPour stop (max hold)") }
                        abortActiveWaterPour(finalizeTelemetry = true, sendHardwareStop = false)
                        resumeSubscriptionExitTimerAfterWaterPour()
                        _state.update { it.copy(waterPourLimitBanner = true) }
                        waterPourLimitHideJob?.cancel()
                        waterPourLimitHideJob =
                            viewModelScope.launch {
                                delay(WATER_POUR_LIMIT_HIDE_MS)
                                _state.update { it.copy(waterPourLimitBanner = false) }
                            }
                    }
            }
    }

    fun waterPourPointerUp() {
        waterPourDebounceJob?.cancel()
        waterPourDebounceJob = null
        waterPourMaxHoldJob?.cancel()
        waterPourMaxHoldJob = null
        if (!waterPourStarted && holdPourRequestUuid == null) {
            resumeSubscriptionExitTimerAfterWaterPour()
            return
        }
        viewModelScope.launch {
            runCatching {
                controllerGateway.sendCommand(
                    RequestCommand.WaterPourByTouch,
                    WaterPourByTouchPayload.stopBody,
                )
            }.onFailure { e ->
                Timber.w(e, "waterPour stop")
                _state.update {
                    it.copy(waterPourError = e.message ?: "Ошибка остановки налива воды")
                }
            }
            abortActiveWaterPour(finalizeTelemetry = true, sendHardwareStop = false)
            resumeSubscriptionExitTimerAfterWaterPour()
        }
    }

    private fun cancelWaterPourGestures() {
        waterPourDebounceJob?.cancel()
        waterPourDebounceJob = null
        waterPourMaxHoldJob?.cancel()
        waterPourMaxHoldJob = null
        waterPourLimitHideJob?.cancel()
        waterPourLimitHideJob = null
        if (isWaterPourSessionActive()) {
            viewModelScope.launch {
                runCatching {
                    controllerGateway.sendCommand(
                        RequestCommand.WaterPourByTouch,
                        WaterPourByTouchPayload.stopBody,
                    )
                }.onFailure { Timber.w(it, "waterPour stop (cancel selection)") }
                abortActiveWaterPour(finalizeTelemetry = true, sendHardwareStop = false)
                resumeSubscriptionExitTimerAfterWaterPour()
            }
        } else {
            viewModelScope.launch {
                holdPourTelemetryCoordinator.cancelHoldPourSession()
            }
            holdPourRequestUuid = null
            _state.update {
                it.copy(isWaterPourActive = false, waterPourLimitBanner = false, waterPourError = null)
            }
            resumeSubscriptionExitTimerAfterWaterPour()
        }
    }

    private fun isWaterPourSessionActive(): Boolean =
        waterPourStarted ||
            holdPourRequestUuid != null ||
            _state.value.isWaterPourActive ||
            waterPourDebounceJob?.isActive == true

    /**
     * Stops an in-flight hold pour once. Callers that already sent hardware stop set [sendHardwareStop] false.
     */
    private suspend fun abortActiveWaterPour(
        finalizeTelemetry: Boolean,
        sendHardwareStop: Boolean = true,
    ) {
        if (!isWaterPourSessionActive() && holdPourRequestUuid == null) return
        waterPourDebounceJob?.cancel()
        waterPourDebounceJob = null
        waterPourMaxHoldJob?.cancel()
        waterPourMaxHoldJob = null
        val hadStartedPour = waterPourStarted || holdPourRequestUuid != null
        waterPourStarted = false
        if (sendHardwareStop && hadStartedPour) {
            runCatching {
                controllerGateway.sendCommand(
                    RequestCommand.WaterPourByTouch,
                    WaterPourByTouchPayload.stopBody,
                )
            }.onFailure { Timber.w(it, "waterPour stop (abort)") }
        }
        if (finalizeTelemetry) {
            finalizeHoldPourTelemetry()
        } else {
            holdPourTelemetryCoordinator.cancelHoldPourSession()
            holdPourRequestUuid = null
        }
        _state.update {
            it.copy(isWaterPourActive = false, waterPourLimitBanner = false, waterPourError = null)
        }
    }

    private fun reactToSubscriptionEntitlementLoss(pourSessionActive: Boolean) {
        waterPourDebounceJob?.cancel()
        waterPourDebounceJob = null
        if (pourSessionActive) {
            viewModelScope.launch { abortActiveWaterPour(finalizeTelemetry = true) }
        }
    }

    private fun waterPourStartPayload(): ByteArray =
        WaterPourByTouchPayload.startPayload(
            flowWaterPourType = _state.value.flowWaterPourType,
            subscriptionActive = _state.value.isSubscriptionActive,
        )

    private suspend fun beginHoldPourTelemetryIfNeeded() {
        val s = _state.value
        val machineId = telemetryService.loadMachineRegistration().machineId
        val effectiveType = PlainWaterEntitlement.effectivePourType(s.flowWaterPourType, s.isSubscriptionActive)
        holdPourRequestUuid =
            holdPourTelemetryCoordinator.beginHoldPourSession(
                clientId = s.scannedSubscriptionClientId,
                machineId = machineId,
                plainWaterType = effectiveType,
                offlineMode = !s.telemetryWsConnected,
            )
    }

    private suspend fun finalizeHoldPourTelemetry() {
        if (holdPourRequestUuid == null) return
        holdPourRequestUuid = null
        holdPourTelemetryCoordinator.finalizeHoldPourSession()
    }

    fun clearSelection() {
        if (_state.value.combinedPaymentConfirmed) return
        viewModelScope.launch { clearSelectionInternal() }
    }

    private suspend fun clearSelectionInternal() {
        cancelWaterPourGestures()
        val wasCombined = _state.value.paymentSheetStep == PaymentSheetStep.Combined
        if (wasCombined && !combinedPaymentSettled.get()) {
            if (!closeCombinedPaymentForCancellation()) return
            stopCombinedPaymentChannels()
            finishPaymentJobForReplacement(paymentJob)
            paymentJob = null
            if (combinedPaymentSettled.get()) return
        } else if (!combinedPaymentSettled.get()) {
            finishPaymentJobForReplacement(paymentJob)
            paymentJob = null
            cardPaymentOrchestrator.cancelActivePayment()
            cancelSbpFlowAwaiting()
        } else {
            paymentJob?.cancel()
            paymentJob = null
        }
        releaseCombinedPaymentSession()
        _state.update {
            it.copy(
                activeContainer = null,
                selectedVolumeMl = null,
                flowBanner = null,
                paymentSheetVisible = false,
                paymentSheetStep = PaymentSheetStep.MethodChoice,
                paymentError = null,
                cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                combinedPaymentConfirmed = false,
                isProcessingPay = false,
                sbpLink = null,
                sbpStatus = SBPStatus.Pending,
                sbpRemainingSeconds = 0,
                isSbpLoading = false,
                isWaterPourActive = false,
                waterPourLimitBanner = false,
                waterPourError = null,
            )
        }
    }

    /**
     * Явный выход в меню после confirmed combined-оплаты и ошибки приготовления.
     * Единственный путь сброса settled/confirmed без повторного pay.
     */
    fun exitCombinedPaymentRecoveryToMenu() {
        viewModelScope.launch { exitCombinedPaymentRecoveryToMenuInternal() }
    }

    private suspend fun exitCombinedPaymentRecoveryToMenuInternal() {
        val s = _state.value
        if (!s.combinedPaymentConfirmed || s.paymentError.isNullOrBlank()) return
        paymentJob?.cancel()
        paymentJob = null
        cancelWaterPourGestures()
        releaseCombinedPaymentSession()
        _state.update {
            it.copy(
                activeContainer = null,
                selectedVolumeMl = null,
                flowBanner = null,
                paymentSheetVisible = false,
                paymentSheetStep = PaymentSheetStep.MethodChoice,
                paymentError = null,
                cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                combinedPaymentConfirmed = false,
                isProcessingPay = false,
                sbpLink = null,
                sbpStatus = SBPStatus.Pending,
                sbpRemainingSeconds = 0,
                isSbpLoading = false,
                isWaterPourActive = false,
                waterPourLimitBanner = false,
                waterPourError = null,
            )
        }
    }

    fun dismissPaymentSheet() {
        if (_state.value.combinedPaymentConfirmed) return
        viewModelScope.launch { dismissPaymentSheetInternal() }
    }

    private suspend fun dismissPaymentSheetInternal() {
        val isCombined = _state.value.paymentSheetStep == PaymentSheetStep.Combined
        if (isCombined) {
            if (!closeCombinedPaymentForCancellation()) return
            stopCombinedPaymentChannels()
            finishPaymentJobForReplacement(paymentJob)
            paymentJob = null
            if (combinedPaymentSettled.get()) return
            releaseCombinedPaymentSession()
        } else {
            finishPaymentJobForReplacement(paymentJob)
            paymentJob = null
            cardPaymentOrchestrator.cancelActivePayment()
            cancelSbpFlowAwaiting()
            resetCombinedPaymentChannelFlags()
        }
        _state.update {
            it.copy(
                paymentSheetVisible = false,
                paymentSheetStep = PaymentSheetStep.MethodChoice,
                paymentError = null,
                cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                isProcessingPay = false,
                sbpLink = null,
                sbpStatus = SBPStatus.Pending,
                sbpRemainingSeconds = 0,
                isSbpLoading = false,
            )
        }
    }

 /** Как `onExitSubscribe`. */
    fun dismissSubscriptionCard() {
        stopSubscriptionExitTimer()
        telemetryService.clearSubscribeUiState()
    }

    /** Сброс idle-таймера выхода из подписки при любом касании экрана. */
    fun resetSubscriptionExitTimer() {
        if (subscriptionExitPausedForPour || isWaterPourSessionActive()) return
        if (_state.value.scannedSubscriptionClientId.isNullOrBlank()) return
        startSubscriptionExitTimer()
    }

    private fun pauseSubscriptionExitTimerForWaterPour() {
        subscriptionExitPausedForPour = true
        if (_state.value.scannedSubscriptionClientId.isNullOrBlank()) return
        stopSubscriptionExitTimer()
        _state.update { it.copy(subscriptionExitRemainingSeconds = SUBSCRIPTION_EXIT_TIMEOUT_SECONDS) }
    }

    private fun resumeSubscriptionExitTimerAfterWaterPour() {
        subscriptionExitPausedForPour = false
        if (_state.value.scannedSubscriptionClientId.isNullOrBlank()) return
        startSubscriptionExitTimer()
    }

    private fun stopSubscriptionExitTimer() {
        subscriptionExitTimerJob?.cancel()
        subscriptionExitTimerJob = null
    }

    private fun startSubscriptionExitTimer() {
        if (subscriptionExitPausedForPour || isWaterPourSessionActive()) return
        stopSubscriptionExitTimer()
        if (_state.value.scannedSubscriptionClientId.isNullOrBlank()) {
            _state.update { it.copy(subscriptionExitRemainingSeconds = 0) }
            return
        }
        _state.update { it.copy(subscriptionExitRemainingSeconds = SUBSCRIPTION_EXIT_TIMEOUT_SECONDS) }
        subscriptionExitTimerJob =
            viewModelScope.launch {
                var remaining = SUBSCRIPTION_EXIT_TIMEOUT_SECONDS
                while (remaining > 0 && isActive) {
                    if (!shouldTickSubscriptionExitTimer(_state.value)) {
                        delay(200)
                        continue
                    }
                    delay(1_000)
                    if (!isActive) return@launch
                    if (!shouldTickSubscriptionExitTimer(_state.value)) continue
                    remaining -= 1
                    _state.update { it.copy(subscriptionExitRemainingSeconds = remaining) }
                }
                if (!isActive) return@launch
                if (remaining <= 0 && !_state.value.scannedSubscriptionClientId.isNullOrBlank()) {
                    subscriptionExitTimerJob = null
                    exitSubscriptionDueToInactivity()
                }
            }
    }

    private fun shouldTickSubscriptionExitTimer(s: DrinkListUiState): Boolean =
        !s.scannedSubscriptionClientId.isNullOrBlank() &&
            !s.paymentSheetVisible &&
            !s.invalidSubscriptionCardVisible &&
            !subscriptionExitPausedForPour &&
            !isWaterPourSessionActive()

    private suspend fun exitSubscriptionDueToInactivity() {
        if (subscriptionExitPausedForPour || isWaterPourSessionActive()) return
        clearSelectionInternal()
        dismissSubscriptionCard()
    }

    fun dismissInvalidSubscriptionCardDialog() {
        _state.update { it.copy(invalidSubscriptionCardVisible = false) }
    }

    fun backToPaymentMethods() {
        if (_state.value.paymentSheetStep == PaymentSheetStep.Combined) {
            dismissPaymentSheet()
            return
        }
        abandonPaymentJobSlot()
        cancelSbpFlow()
        _state.update {
            it.copy(
                paymentSheetStep = PaymentSheetStep.MethodChoice,
                paymentError = null,
                cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                isProcessingPay = false,
                sbpLink = null,
                sbpStatus = SBPStatus.Pending,
                sbpRemainingSeconds = 0,
                isSbpLoading = false,
            )
        }
    }

 /**
 *
 * при `volumeMl >= выбранного объёма` (подписка или бесплатный литр) — налив по остатку без терминала;
 * при нехватке объёма — модалка оплаты напитка (не экран тарифов).
 */
    fun primaryAction(onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val container = s.activeContainer
            val volume = s.selectedVolumeMl
            if (container == null || volume == null) {
                _state.update {
                    it.copy(flowBanner = "Выберите напиток и объём", flowBannerIsError = true)
                }
                return@launch
            }
            if (container.isUnavailable()) {
                _state.update {
                    it.copy(flowBanner = "Напиток временно недоступен", flowBannerIsError = true)
                }
                return@launch
            }
            preparingManager.validateDrinkPreparation(container.product.taste.id)?.let { error ->
                val message = error.message ?: "Автомат не готов к приготовлению"
                _state.update {
                    it.copy(
                        flowBanner = message,
                        flowBannerIsError = true,
                        paymentError = message,
                    )
                }
                return@launch
            }

            val hasSubCard = !s.scannedSubscriptionClientId.isNullOrBlank()
            val subscriptionVolumeEnough =
                hasSubCard &&
                    s.subscriptionVolumeMl >= volume

 // Нет отсканированной подписки — модалка с СБП и картой (как wiva `PaymentModal`).
 // Раньше при freeMode сразу вызывали налив без оплаты — тогда нельзя было дойти до СБП/карты.
 // Быстрый налив без оплаты: кнопка «Налить без оплаты (разработка)» в модалке ([CustomerPaymentSheet]).
            if (!hasSubCard) {
                openCombinedDrinkPayment(onNavigateToPreparing)
                return@launch
            }

 // Остаток по карте хватает (подписка или бесплатный литр) — готовка + useSubscriptionSaleTopic.
            if (subscriptionVolumeEnough) {
                _state.update { it.copy(isProcessingPay = true, flowBanner = null) }
                try {
                    runChooseAndNavigate(
                        container,
                        volume,
                        s,
                        onNavigateToPreparing,
                        saleTotalPriceRub = 0.0,
                        salePayMethod = "SUBSCRIBE",
                    )
                } catch (e: Exception) {
                    Timber.e(e, "primaryAction pour by subscription")
                    _state.update {
                        it.copy(
                            flowBanner = e.message ?: "Ошибка готовки",
                            flowBannerIsError = true,
                        )
                    }
                } finally {
                    _state.update { it.copy(isProcessingPay = false) }
                }
                return@launch
            }

 // Подписка есть, но объёма не хватает (или неактивна) — параллельная оплата напитка.
            openCombinedDrinkPayment(onNavigateToPreparing)
        }
    }

    private fun openCombinedDrinkPayment(
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        if (combinedPaymentSettled.get()) return
        _state.update {
            it.copy(
                paymentSheetVisible = true,
                paymentSheetStep = PaymentSheetStep.Combined,
                paymentError = null,
                cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                combinedPaymentConfirmed = false,
            )
        }
        startCombinedDrinkPayment(onNavigateToPreparing)
    }

    fun startCombinedDrinkPayment(
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        if (combinedPaymentSettled.get()) return
        val previousPaymentJob = paymentJob
        paymentJob =
            viewModelScope.launch {
                if (!closeCombinedPaymentForCancellation()) return@launch
                cancelSbpFlowAwaiting()
                finishPaymentJobForReplacement(previousPaymentJob)
                resetCombinedPaymentChannelFlags()
                openCombinedPaymentSession()
                _state.update { it.copy(combinedPaymentConfirmed = false) }
                val s = _state.value
                val container = s.activeContainer ?: return@launch
                val volume = s.selectedVolumeMl ?: return@launch
                val priceRub =
                    container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
                if (priceRub <= 0) {
                    _state.update {
                        it.copy(
                            paymentError = "Некорректная сумма оплаты",
                            isSbpLoading = false,
                            isProcessingPay = false,
                        )
                    }
                    return@launch
                }

                _state.update {
                    it.copy(
                        paymentSheetStep = PaymentSheetStep.Combined,
                        paymentSheetVisible = true,
                        isProcessingPay = true,
                        paymentError = null,
                        sbpLink = null,
                        sbpStatus = SBPStatus.Pending,
                        sbpRemainingSeconds = COMBINED_PAYMENT_TIMEOUT_SECONDS,
                        isSbpLoading = true,
                        cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                    )
                }

                startCombinedPaymentTimer()

                coroutineScope {
                    launch {
                        runCombinedCardChannel(
                            container = container,
                            volume = volume,
                            onNavigateToPreparing = onNavigateToPreparing,
                        )
                    }
                    launch {
                        runCombinedSbpChannel(
                            priceRub = priceRub,
                            onNavigateToPreparing = onNavigateToPreparing,
                        )
                    }
                }
            }
    }

    private suspend fun runCombinedCardChannel(
        container: DrinkContainer,
        volume: Int,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        val priceRub = container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
        try {
            when (
                val payResult =
                    cardPaymentOrchestrator.pay(
                        TerminalProductType.Drink,
                        priceRub,
                        container.containerNumber,
                        sbp = false,
                    )
            ) {
                CardPaymentResult.Success -> {
                    val current = _state.value
                    if (current.activeContainer != container ||
                        current.selectedVolumeMl != volume ||
                        current.paymentSheetStep != PaymentSheetStep.Combined
                    ) {
                        return
                    }
                    onCombinedPaymentWon(
                        winner = CombinedPaymentWinner.Card,
                        container = container,
                        volume = volume,
                        onNavigateToPreparing = onNavigateToPreparing,
                    )
                }
                is CardPaymentResult.Failed -> {
                    combinedCardFailed = true
                    maybeFinishCombinedBothFailed()
                }
                CardPaymentResult.Cancelled -> {
                    combinedCardFailed = true
                    maybeFinishCombinedBothFailed()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "combined card channel")
            combinedCardFailed = true
            maybeFinishCombinedBothFailed()
        }
    }

    private suspend fun runCombinedSbpChannel(
        priceRub: Int,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        getSbpLinkWithDuplicateRetries(priceRub).fold(
            onSuccess = { link ->
                _state.update {
                    it.copy(
                        sbpLink = link,
                        isSbpLoading = false,
                        sbpStatus = SBPStatus.Pending,
                    )
                }
                startCombinedSbpPolling(
                    orderId = link.orderId,
                    priceRub = priceRub,
                    onNavigateToPreparing = onNavigateToPreparing,
                )
            },
            onFailure = { e ->
                Timber.w(e, "Combined SBP: не удалось получить QR")
                combinedSbpFailed = true
                _state.update { it.copy(isSbpLoading = false) }
                val message =
                    if (
                        e.message?.contains("Unable to resolve host") == true ||
                            e.message?.contains("timeout", ignoreCase = true) == true
                    ) {
                        "СБП недоступен. Проверьте интернет-соединение."
                    } else {
                        e.message ?: "Ошибка оплаты СБП"
                    }
                noteCombinedSbpTerminalError(message)
                maybeFinishCombinedBothFailed()
            },
        )
    }

    private fun startCombinedSbpPolling(
        orderId: String,
        priceRub: Int,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        sbpPollingJob?.cancel()
        sbpPollingJob =
            viewModelScope.launch {
                while (isActive) {
                    if (combinedPaymentSettled.get()) return@launch
                    checkSBPStatusUseCase(orderId).fold(
                        onSuccess = { status ->
                            _state.update { it.copy(sbpStatus = status) }
                            when (status) {
                                SBPStatus.Success -> {
                                    val s = _state.value
                                    val container = s.activeContainer
                                    val volume = s.selectedVolumeMl
                                    if (s.paymentSheetStep != PaymentSheetStep.Combined ||
                                        s.sbpLink?.orderId != orderId
                                    ) {
                                        return@launch
                                    }
                                    if (container == null || volume == null) {
                                        return@launch
                                    }
                                    onCombinedPaymentWon(
                                        winner = CombinedPaymentWinner.Sbp,
                                        container = container,
                                        volume = volume,
                                        onNavigateToPreparing = onNavigateToPreparing,
                                    )
                                    return@launch
                                }
                                is SBPStatus.Failed -> {
                                    combinedSbpFailed = true
                                    noteCombinedSbpTerminalError(
                                        status.reason.ifBlank { "Оплата отклонена" },
                                    )
                                    maybeFinishCombinedBothFailed()
                                    return@launch
                                }
                                SBPStatus.Cancelled -> {
                                    combinedSbpFailed = true
                                    noteCombinedSbpTerminalError("Оплата отменена")
                                    maybeFinishCombinedBothFailed()
                                    return@launch
                                }
                                SBPStatus.Pending -> Unit
                            }
                        },
                        onFailure = { error ->
                            Timber.w(error, "Combined SBP status poll failed orderId=%s", orderId)
                        },
                    )
                    delay(5_000)
                }
            }
    }

    private fun startCombinedPaymentTimer() {
        combinedPaymentTimerJob?.cancel()
        combinedPaymentTimerJob =
            viewModelScope.launch {
                var remaining = COMBINED_PAYMENT_TIMEOUT_SECONDS
                while (remaining > 0 && isActive && !combinedPaymentSettled.get()) {
                    delay(1_000)
                    remaining -= 1
                    _state.update { it.copy(sbpRemainingSeconds = remaining) }
                }
                if (!combinedPaymentSettled.get() && isActive) {
                    // Не даём cleanup отменить текущую timer-coroutine до сброса UI.
                    combinedPaymentTimerJob = null
                    clearSelectionInternal()
                }
            }
    }

    private suspend fun onCombinedPaymentWon(
        winner: CombinedPaymentWinner,
        container: DrinkContainer,
        volume: Int,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        if (!claimCombinedPaymentWinner()) {
            return
        }
        _state.update { it.copy(cardPaymentUiStatus = CardPaymentUiStatus.Success) }
        combinedPaymentTimerJob?.cancel()
        when (winner) {
            CombinedPaymentWinner.Card -> {
                cancelSbpJobs()
                cancelSbpFlow()
            }
            CombinedPaymentWinner.Sbp -> {
                sbpTimerJob?.cancel()
                sbpRetryJob?.cancel()
                cardPaymentOrchestrator.cancelActivePayment()
            }
        }
        val s = _state.value
        _state.update {
            it.copy(
                isProcessingPay = true,
                paymentError = null,
                combinedPaymentConfirmed = true,
                cardPaymentUiStatus = CardPaymentUiStatus.Success,
            )
        }
        try {
            paidTerminalThenPour(
                container = container,
                volume = volume,
                s = s,
                sbp = winner == CombinedPaymentWinner.Sbp,
                cardAlreadyPaid = winner == CombinedPaymentWinner.Card,
                onNavigateToPreparing = onNavigateToPreparing,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "onCombinedPaymentWon")
            _state.update {
                it.copy(
                    paymentError = COMBINED_PAID_PREPARE_FAILED_MESSAGE,
                    isProcessingPay = false,
                    isSbpLoading = false,
                )
            }
        }
    }

    private fun noteCombinedSbpTerminalError(message: String) {
        if (combinedPaymentSettled.get()) return
        combinedSbpErrorMessage = message
        maybeFinishCombinedBothFailed()
    }

    private fun maybeFinishCombinedBothFailed() {
        if (combinedPaymentSettled.get()) return
        if (!combinedCardFailed || !combinedSbpFailed) return
        val message =
            combinedSbpErrorMessage?.takeIf { it.isNotBlank() }
                ?: "Не удалось оплатить. Попробуйте снова."
        viewModelScope.launch {
            stopCombinedPaymentChannels()
            _state.update {
                it.copy(
                    paymentError = message,
                    isProcessingPay = false,
                    isSbpLoading = false,
                )
            }
        }
    }

    private suspend fun stopCombinedPaymentChannels() {
        if (combinedPaymentSettled.get()) return
        combinedPaymentTimerJob?.cancel()
        combinedPaymentTimerJob = null
        cancelSbpJobs()
        val orderId = _state.value.sbpLink?.orderId
        if (!orderId.isNullOrBlank()) {
            runCatching { sbpRepository.cancelSBPLink(orderId) }
                .onFailure { Timber.w(it, "Combined SBP cancel failed orderId=%s", orderId) }
        }
        cardPaymentOrchestrator.cancelActivePayment()
    }

    private fun resetCombinedPaymentChannelFlags() {
        combinedCardFailed = false
        combinedSbpFailed = false
        combinedSbpErrorMessage = null
        combinedPaymentTimerJob?.cancel()
        combinedPaymentTimerJob = null
    }

    private fun openCombinedPaymentSession() {
        synchronized(combinedPaymentSessionLock) {
            combinedPaymentClosed = false
            combinedPaymentSettled.set(false)
        }
    }

    private fun claimCombinedPaymentWinner(): Boolean =
        synchronized(combinedPaymentSessionLock) {
            if (combinedPaymentClosed || combinedPaymentSettled.get()) {
                false
            } else {
                combinedPaymentSettled.set(true)
                true
            }
        }

    private fun closeCombinedPaymentForCancellation(): Boolean =
        synchronized(combinedPaymentSessionLock) {
            if (combinedPaymentClosed || combinedPaymentSettled.get()) {
                false
            } else {
                combinedPaymentClosed = true
                true
            }
        }

    private fun releaseCombinedPaymentSession() {
        synchronized(combinedPaymentSessionLock) {
            combinedPaymentSettled.set(false)
            combinedPaymentClosed = false
        }
        resetCombinedPaymentChannelFlags()
    }

 /**
 * Только при [Free mode](DrinkListUiState.freeMode): «как раньше» — налив без терминала и без оплаты.
 * Вызывается с шага выбора способа оплаты в [CustomerPaymentSheet].
 */
    fun devPourWithoutPayment(
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
    ) {
        if (_state.value.combinedPaymentConfirmed || combinedPaymentSettled.get()) return
        viewModelScope.launch {
            if (_state.value.paymentSheetStep == PaymentSheetStep.Combined) {
                if (!closeCombinedPaymentForCancellation()) return@launch
                stopCombinedPaymentChannels()
            }
            finishPaymentJobForReplacement(paymentJob)
            paymentJob = null
            releaseCombinedPaymentSession()
            val s = _state.value
            val container = s.activeContainer ?: return@launch
            val volume = s.selectedVolumeMl ?: return@launch
            if (!s.freeMode) return@launch
            _state.update {
                it.copy(
                    paymentSheetVisible = false,
                    paymentSheetStep = PaymentSheetStep.MethodChoice,
                    paymentError = null,
                    sbpLink = null,
                    sbpStatus = SBPStatus.Pending,
                    sbpRemainingSeconds = 0,
                    isSbpLoading = false,
                    isProcessingPay = true,
                    flowBanner = null,
                )
            }
            try {
                runChooseAndNavigate(
                    container,
                    volume,
                    s,
                    onNavigateToPreparing,
                    saleTotalPriceRub = 0.0,
                    salePayMethod = "FREE",
                )
            } catch (e: Exception) {
                Timber.e(e, "devPourWithoutPayment")
                _state.update {
                    it.copy(
                        flowBanner = e.message ?: "Ошибка готовки",
                        flowBannerIsError = true,
                    )
                }
            } finally {
                _state.update { it.copy(isProcessingPay = false) }
            }
        }
    }

 /** Шаг «карта» в `PaymentModal.tsx` (автозапуск оплаты). */
    fun startCardPayment(onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit) {
        val previousPaymentJob = paymentJob
        paymentJob =
            viewModelScope.launch {
                finishPaymentJobForReplacement(previousPaymentJob)
                val s = _state.value
                val container = s.activeContainer ?: return@launch
                val volume = s.selectedVolumeMl ?: return@launch
                _state.update {
                    it.copy(
                        paymentSheetStep = PaymentSheetStep.Card,
                        isProcessingPay = true,
                        paymentError = null,
                    )
                }
                try {
                    paidTerminalThenPour(container, volume, s, sbp = false, onNavigateToPreparing)
                } catch (e: Exception) {
                    Timber.e(e, "startCardPayment")
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _state.update {
                            it.copy(
                                paymentError = e.message ?: "Ошибка оплаты. Попробуйте снова.",
                                paymentSheetStep = PaymentSheetStep.Card,
                            )
                        }
                    }
                } finally {
                    _state.update { it.copy(isProcessingPay = false) }
                }
            }
    }

    fun openSbpStep(onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit) {
        val previousPaymentJob = paymentJob
        _state.update {
            it.copy(
                paymentSheetStep = PaymentSheetStep.Sbp,
                paymentError = null,
                sbpLink = null,
                sbpStatus = SBPStatus.Pending,
                sbpRemainingSeconds = 0,
                isSbpLoading = true,
            )
        }
        val s = _state.value
        val container = s.activeContainer ?: return
        val volume = s.selectedVolumeMl ?: return
        val priceRub = container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
        if (priceRub <= 0) {
            _state.update {
                it.copy(
                    paymentError = "Некорректная сумма оплаты",
                    isSbpLoading = false,
                )
            }
            return
        }
        startSbpPaymentFlow(
            priceRub = priceRub,
            onSbpSuccess = { completeSbpPayment(onNavigateToPreparing) },
            previousPaymentJob = previousPaymentJob,
        )
    }

 /**
 * СБП по сумме в рублях: QR → polling → [onSbpSuccess] (напиток: терминал+готовка; подписка: saleSubscribeTopic).
 */
    private fun startSbpPaymentFlow(
        priceRub: Int,
        onSbpSuccess: () -> Unit,
        previousPaymentJob: Job?,
    ) {
        cancelSbpJobs()
        paymentJob =
            viewModelScope.launch {
                finishPaymentJobForReplacement(previousPaymentJob)
                if (priceRub <= 0) {
                    _state.update {
                        it.copy(
                            paymentError = "Некорректная сумма оплаты",
                            isSbpLoading = false,
                        )
                    }
                    return@launch
                }

                val timeout = sbpRepository.getSettings().timeoutInSeconds.coerceIn(30, 600)
                _state.update {
                    it.copy(
                        paymentError = null,
                        sbpLink = null,
                        sbpStatus = SBPStatus.Pending,
                        sbpRemainingSeconds = timeout,
                        isSbpLoading = true,
                    )
                }

                getSbpLinkWithDuplicateRetries(priceRub).fold(
                    onSuccess = { link ->
                        _state.update {
                            it.copy(
                                sbpLink = link,
                                isSbpLoading = false,
                                sbpStatus = SBPStatus.Pending,
                            )
                        }
                        startSbpPolling(
                            orderId = link.orderId,
                            priceRub = priceRub,
                            onSbpSuccess = onSbpSuccess,
                        )
                        startSbpTimer()
                    },
                    onFailure = { e ->
                        Timber.w(e, "SBP: не удалось получить QR (generate_qr)")
                        val message =
                            if (
                                e.message?.contains("Unable to resolve host") == true ||
                                e.message?.contains("timeout", ignoreCase = true) == true
                            ) {
                                "СБП недоступен. Проверьте интернет-соединение."
                            } else {
                                e.message ?: "Ошибка оплаты СБП"
                            }
                        _state.update { it.copy(paymentError = message, isSbpLoading = false) }
                    },
                )
            }
    }

 /**
 * Повторная выдача QR без выхода из оплаты (напиток или подписка).
 * Дублирующий заказ на стороне Paymaster раньше лечился «выйти и зайти» — здесь тот же сценарий в один тап.
 */
    fun retrySbpPayment(onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit) {
        if (_state.value.paymentSheetStep != PaymentSheetStep.Sbp) return
        openSbpStep(onNavigateToPreparing)
    }

 /** Несколько попыток [getSBPLinkUseCase] при ответе про дубликат заказа (см. [PaymasterQPayHelper.generateReqn]). */
    private suspend fun getSbpLinkWithDuplicateRetries(priceRub: Int): Result<SBPLink> {
        val maxAttempts = 4
        var last: Throwable? = null
        repeat(maxAttempts) { attempt ->
            val r = getSBPLinkUseCase(priceRub * 100)
            if (r.isSuccess) return r
            val e = r.exceptionOrNull() ?: return r
            last = e
            if (isSbpDuplicateOrderMessage(e.message) && attempt < maxAttempts - 1) {
                Timber.w(e, "SBP generate_qr duplicate-like, retry %d/%d", attempt + 1, maxAttempts)
                delay((300L * (attempt + 1)).coerceAtMost(1500L))
                return@repeat
            }
            return Result.failure(e)
        }
        return Result.failure(last ?: IllegalStateException("Ошибка оплаты СБП"))
    }

    private fun isSbpDuplicateOrderMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return m.contains("duplicate") ||
            m.contains("dublicate") ||
            m.contains("дубликат")
    }

    private fun startSbpPolling(
        orderId: String,
        priceRub: Int,
        onSbpSuccess: () -> Unit,
    ) {
        sbpPollingJob?.cancel()
        sbpPollingJob =
            viewModelScope.launch {
                while (isActive) {
                    checkSBPStatusUseCase(orderId).fold(
                        onSuccess = { status ->
                            _state.update { it.copy(sbpStatus = status) }
                            when (status) {
                                SBPStatus.Success -> {
                                    cancelSbpJobs()
                                    onSbpSuccess()
                                }
                                is SBPStatus.Failed -> {
                                    cancelSbpJobs()
                                    if (status.reason == "DENIED") {
                                        sbpRetryJob =
                                            viewModelScope.launch {
                                                delay(5_000)
                                                if (isActive) {
                                                    startSbpPaymentFlow(
                                                        priceRub = priceRub,
                                                        onSbpSuccess = onSbpSuccess,
                                                        previousPaymentJob = paymentJob,
                                                    )
                                                }
                                            }
                                    } else {
                                        _state.update {
                                            it.copy(
                                                paymentError = status.reason.ifBlank { "Оплата отклонена" },
                                                isSbpLoading = false,
                                            )
                                        }
                                    }
                                }
                                SBPStatus.Cancelled -> {
                                    cancelSbpJobs()
                                    _state.update {
                                        it.copy(
                                            paymentError = "Оплата отменена",
                                            isSbpLoading = false,
                                        )
                                    }
                                }
                                SBPStatus.Pending -> Unit
                            }
                        },
                        onFailure = { error ->
                            Timber.w(error, "SBP status poll failed orderId=%s", orderId)
                        },
                    )
                    delay(5_000)
                }
            }
    }

    private fun startSbpTimer() {
        sbpTimerJob?.cancel()
        sbpTimerJob =
            viewModelScope.launch {
                while (_state.value.sbpRemainingSeconds > 0) {
                    delay(1_000)
                    _state.update { it.copy(sbpRemainingSeconds = it.sbpRemainingSeconds - 1) }
                }
                // Не отменяем polling на границе таймера: платёж может подтверждаться банком с задержкой.
                // Пользователь может вернуться вручную, а поздний SUCCESS всё ещё запустит приготовление.
                _state.update {
                    it.copy(paymentError = "QR истёк. Если вы уже оплатили, дождитесь подтверждения.")
                }
            }
    }

    private fun completeSbpPayment(onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit) {
        val previousPaymentJob = paymentJob
        paymentJob =
            viewModelScope.launch {
                finishPaymentJobForReplacement(previousPaymentJob)
                val s = _state.value
                val container = s.activeContainer
                val volume = s.selectedVolumeMl
                if (container == null || volume == null) {
                    Timber.e("SBP paid, but drink selection is missing")
                    _state.update {
                        it.copy(
                            paymentError = "Оплата прошла, но выбор напитка потерян. Обратитесь к администратору.",
                            paymentSheetStep = PaymentSheetStep.Sbp,
                            isSbpLoading = false,
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(isProcessingPay = true, paymentError = null) }
                try {
                    paidTerminalThenPour(container, volume, s, sbp = true, onNavigateToPreparing)
                } catch (e: Exception) {
                    Timber.e(e, "completeSbpPayment")
                    _state.update {
                        it.copy(
                            paymentError = e.message ?: "Ошибка подтверждения оплаты СБП",
                            paymentSheetStep = PaymentSheetStep.Sbp,
                        )
                    }
                } finally {
                    _state.update { it.copy(isProcessingPay = false, isSbpLoading = false) }
                }
            }
    }

    private fun cancelSbpJobs() {
        sbpPollingJob?.cancel()
        sbpTimerJob?.cancel()
        sbpRetryJob?.cancel()
    }

    private fun cancelSbpFlow() {
        val orderId = _state.value.sbpLink?.orderId
        cancelSbpJobs()
        if (!orderId.isNullOrBlank()) {
            viewModelScope.launch {
                sbpRepository.cancelSBPLink(orderId)
            }
        }
    }

    private suspend fun cancelSbpFlowAwaiting() {
        val orderId = _state.value.sbpLink?.orderId
        cancelSbpJobs()
        if (!orderId.isNullOrBlank()) {
            runCatching { sbpRepository.cancelSBPLink(orderId) }
                .onFailure { Timber.w(it, "SBP cancel failed orderId=%s", orderId) }
        }
    }

    private suspend fun paidTerminalThenPour(
        container: DrinkContainer,
        volume: Int,
        s: DrinkListUiState,
        sbp: Boolean,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
        cardAlreadyPaid: Boolean = false,
    ) {
        val priceRub =
            container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
        DrinkListCardPaymentFlow.runDrinkPaymentBeforePour(
            container = container,
            volume = volume,
            sbp = sbp,
            cardPaymentOrchestrator = cardPaymentOrchestrator,
            controllerSbpNotifyService = controllerSbpNotifyService,
            cardAlreadyPaid = cardAlreadyPaid,
        )
        val mock = configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"
        if (sbp && mock) {
            delay(250)
            controllerGateway.simulateResponseForTests(
                ResponseCommand.PaymentSystemsPaxStatus,
                byteArrayOf(4),
            )
            delay(400)
        } else if (sbp) {
            delay(600)
        }
        runChooseAndNavigate(
            container,
            volume,
            s,
            onNavigateToPreparing,
            saleTotalPriceRub = priceRub.toDouble(),
            salePayMethod = if (sbp) "SBP" else "CARD",
        )
    }

    private suspend fun runChooseAndNavigate(
        container: DrinkContainer,
        volume: Int,
        s: DrinkListUiState,
        onNavigateToPreparing: (tasteId: Int, productName: String, estSeconds: Int, mediaKey: String?, payMethod: String, priceRub: Int) -> Unit,
        saleTotalPriceRub: Double = 0.0,
        salePayMethod: String? = null,
    ) {
        val subClientId = s.scannedSubscriptionClientId
        val subscriptionPourContext =
            if (!subClientId.isNullOrBlank() && salePayMethod == "SUBSCRIBE") {
                val requestUuid =
                    pendingSubscriptionPourRequestUuid
                        ?: UUID.randomUUID().toString().also { pendingSubscriptionPourRequestUuid = it }
                val machineId = telemetryService.loadMachineRegistration().machineId
                SubscriptionPourContext(
                    clientId = subClientId,
                    requestUuid = requestUuid,
                    saleId = UUID.randomUUID().toString(),
                    machineId = machineId,
                    offlineMode = s.telemetryWsConnected.not(),
                )
            } else {
                null
            }
        val result =
            preparingManager.prepareDrink(
                tasteId = container.product.taste.id,
                volumeMl = volume,
                waterOption = s.waterOption,
                concentrationRatio = s.concentration.toRatio(),
                concentration = s.concentration,
                saleTotalPriceRub = saleTotalPriceRub,
                salePayMethod = salePayMethod,
                subscriptionPourContext = subscriptionPourContext,
            )
        when (result) {
            is PrepareDrinkResult.Ok -> {
                val linePriceRub =
                    container.product.dPrices.firstOrNull { it.volume == volume }?.priceRub ?: 0
                val navPriceRub =
                    when {
                        salePayMethod == null ||
                            salePayMethod.equals("SUBSCRIBE", ignoreCase = true) ||
                            salePayMethod.equals("FREE", ignoreCase = true) -> 0
                        saleTotalPriceRub > 0 -> saleTotalPriceRub.toInt()
                        else -> linePriceRub
                    }
                onNavigateToPreparing(
                    container.product.taste.id,
                    container.product.name,
                    result.estSeconds,
                    container.product.taste.mediaKey,
                    payMethodNavKey(salePayMethod),
                    navPriceRub,
                )
                if (!subClientId.isNullOrBlank()) {
                    pendingSubscriptionPourRequestUuid = null
                }
                _state.update {
                    it.copy(
                        activeContainer = null,
                        selectedVolumeMl = null,
                        flowBanner = null,
                        paymentSheetVisible = false,
                        paymentSheetStep = PaymentSheetStep.MethodChoice,
                        paymentError = null,
                        cardPaymentUiStatus = CardPaymentUiStatus.AttachCard,
                        combinedPaymentConfirmed = false,
                        isProcessingPay = false,
                        sbpLink = null,
                        sbpStatus = SBPStatus.Pending,
                        sbpRemainingSeconds = 0,
                        isSbpLoading = false,
                    )
                }
                releaseCombinedPaymentSession()
            }
            is PrepareDrinkResult.Error -> {
                val genericMsg = result.message ?: result.errorCode ?: "Ошибка готовки"
                _state.update { prev ->
                    val overlayMsg =
                        if (prev.combinedPaymentConfirmed) {
                            COMBINED_PAID_PREPARE_FAILED_MESSAGE
                        } else if (prev.paymentSheetVisible) {
                            genericMsg
                        } else {
                            prev.paymentError
                        }
                    prev.copy(
                        flowBanner = genericMsg,
                        flowBannerIsError = true,
                        isProcessingPay = false,
                        paymentError = overlayMsg,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        cancelWaterPourGestures()
        // ViewModelScope завершается вместе с onCleared; await здесь невозможен — best-effort cleanup.
        viewModelScope.launch {
            if (!combinedPaymentSettled.get()) {
                stopCombinedPaymentChannels()
            }
        }
        releaseCombinedPaymentSession()
        cancelSbpFlow()
        paymentJob?.cancel()
        runCatching {
            runBlocking {
                withTimeout(2_000) {
                    cardPaymentOrchestrator.cancelActivePayment()
                }
            }
        }
        paymentClearingScope.cancel()
        super.onCleared()
    }

    /** Unit-test seam: simulate mid-hold subscription/card loss. */
    internal suspend fun abortActiveWaterPourForUnitTests(finalizeTelemetry: Boolean = true) {
        abortActiveWaterPour(finalizeTelemetry = finalizeTelemetry, sendHardwareStop = true)
    }

    /** Unit-test seam: mark pour active without debounce/hardware start. */
    internal fun markWaterPourActiveForUnitTests(holdRequestUuid: String = "unit-test-hold") {
        waterPourStarted = true
        holdPourRequestUuid = holdRequestUuid
        _state.update { it.copy(isWaterPourActive = true) }
    }

    internal fun isSubscriptionExitTimerRunningForUnitTests(): Boolean =
        subscriptionExitTimerJob?.isActive == true

    internal fun waterPourStartPayloadForUnitTests(): ByteArray = waterPourStartPayload()

    /** Только для unit-тестов: начальное состояние без пользовательских сценариев. */
    internal fun setUiStateForUnitTests(state: DrinkListUiState) {
        _state.value = state
    }

    internal fun isCombinedPaymentSettledForUnitTests(): Boolean = combinedPaymentSettled.get()

    internal suspend fun exitCombinedPaymentRecoveryToMenuForUnitTests() {
        exitCombinedPaymentRecoveryToMenuInternal()
    }

    internal suspend fun clearSelectionForUnitTests() {
        clearSelectionInternal()
    }

    internal suspend fun expireCombinedPaymentForUnitTests() {
        combinedPaymentTimerJob = null
        clearSelectionInternal()
    }

    /** Unit-test seam: cancel viewModelScope jobs without blocking payment cancel. */
    internal fun clearForUnitTests() {
        cancelWaterPourGestures()
        releaseCombinedPaymentSession()
        cancelSbpFlow()
        paymentJob?.cancel()
        paymentJob = null
        subscriptionExitTimerJob?.cancel()
        paymentClearingScope.cancel()
        super.onCleared()
    }

    internal fun applyTerminalBannerForCombinedStatusTest(terminalBanner: String) {
        _state.update { st ->
            st.copy(
                cardPaymentUiStatus =
                    resolveCombinedCardPaymentUiStatus(
                        terminalBanner = terminalBanner,
                        current = st.cardPaymentUiStatus,
                        paymentConfirmed = st.combinedPaymentConfirmed,
                    ),
            )
        }
    }

    private fun payMethodNavKey(salePayMethod: String?): String =
        when (salePayMethod) {
            "SBP" -> "sbp"
            "CARD" -> "card"
            "SUBSCRIBE" -> "subscribe"
            else -> "none"
        }

    companion object {
        const val COMBINED_PAYMENT_TIMEOUT_SECONDS = 60
        const val COMBINED_PAID_PREPARE_FAILED_MESSAGE =
            "Оплата прошла, но напиток не приготовлен. Обратитесь к оператору."
        const val SUBSCRIPTION_EXIT_TIMEOUT_SECONDS = 10

        private const val WATER_POUR_DEBOUNCE_MS = 400L
        const val WATER_POUR_MAX_MS = 30_000L
        const val WATER_POUR_LIMIT_HIDE_MS = 4_000L

 /** UUID для кнопки «Эмуляция QR подписки» (контракт statusSubscribeTopic.body). */
        const val EMULATED_SUBSCRIPTION_CLIENT_UUID = "2caaf0b2-2b7f-4c09-9bef-dafd984c9a66"

 /**
 * Доп. клиенты для проверки сценария «доступен бесплатный напиток по подписке»
 * (кнопки mock QR на экране напитков,.
 */
        internal val FREE_SUBSCRIPTION_EMULATION_TEST_CLIENT_IDS: List<String> =
            listOf(
                "884ae012-6c49-44d1-8227-a6abfec14552",
                "0a283a81-e92e-421e-9fc1-46c219459c7e",
            )

 /** Стоп WaterPourByTouch (0xD0): все нули. */
        val WATER_POUR_STOP_BODY: ByteArray = WaterPourByTouchPayload.stopBody

 /** ISO-8601 UTC без Date.toInstant (minSdk 25). */
        private fun isoUtcTimestamp(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }
}
