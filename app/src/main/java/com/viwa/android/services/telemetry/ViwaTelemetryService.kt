package com.viwa.android.services.telemetry

import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryLoyaltySyncHandler
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.RegistrationKeyUtils
import com.viwa.android.data.remote.telemetry.mvp.SimpleTelemetryCoordinator
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.domain.telemetry.PourEventSnapshot
import com.viwa.android.domain.telemetry.PlainWaterType
import com.viwa.android.domain.telemetry.PourKind
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.offline.OfflinePourAuthorizationService
import com.viwa.android.data.telemetry.loyalty.LoyaltyWaterUseRequest
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import com.viwa.android.domain.subscription.LoyaltyPaymentException
import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatusResult
import com.viwa.android.domain.subscription.SubscriptionSaleParams
import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.local.db.JsonStoreKeys
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/**
 * Фасад телеметрии: MVP WS через [SimpleTelemetryCoordinator].
 * Loyalty envelope v2 — через [MvpTelemetryWebSocketManager].
 */
@Singleton
class ViwaTelemetryService
@Inject
constructor(
    private val configRepository: ConfigRepository,
    private val mvpCoordinator: SimpleTelemetryCoordinator,
    private val wsManager: MvpTelemetryWebSocketManager,
    private val dispenseSyncCoordinator: TelemetryDispenseSyncCoordinator,
    private val offlinePourAuthorizationService: OfflinePourAuthorizationService,
    @AppIoScope private val scope: CoroutineScope,
) {
    private companion object {
        const val SUBSCRIPTION_SALE_TIMEOUT_MS = 60_000L
    }

    private enum class PendingLoyaltyKind {
        STATUS_GET,
        LEVELS_LIST,
        WATER_USE,
        PAYMENT_INIT,
        PAYMENT_STATUS_GET,
        PAYMENT_COMPLETE,
        SUBSCRIBE_SALE,
        SUBSCRIBE_CANCEL,
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    val connectionState: StateFlow<ConnectionState> = mvpCoordinator.connectionState

    private val subscriptionSaleTimers = mutableMapOf<String, Job>()

    private val _subscribeInfo = MutableStateFlow<SubscribeInformationState?>(null)
    val subscribeInfo: StateFlow<SubscribeInformationState?> = _subscribeInfo

    private val _subscriptionLevels = MutableStateFlow<List<SubscriptionLevelItem>?>(null)
    val subscriptionLevels: StateFlow<List<SubscriptionLevelItem>?> = _subscriptionLevels

    private val _loyaltyCardClientScans =
        MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val loyaltyCardClientScans: SharedFlow<String> = _loyaltyCardClientScans.asSharedFlow()

    private val _invalidLoyaltyCardScans =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val invalidLoyaltyCardScans: SharedFlow<Unit> = _invalidLoyaltyCardScans.asSharedFlow()

    private val _offlineLoyaltyDenyReason =
        MutableSharedFlow<OfflineAuthorizationReason>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val offlineLoyaltyDenyReason: SharedFlow<OfflineAuthorizationReason> = _offlineLoyaltyDenyReason.asSharedFlow()

    private val pendingLoyaltyRequests = ConcurrentHashMap<String, PendingLoyaltyKind>()
    private val pendingLoyaltyAcks = ConcurrentHashMap<String, CompletableDeferred<Result<JsonObject>>>()
    private val sentWaterUseRequestUuids = ConcurrentHashMap.newKeySet<String>()
    private var lastStatusClientId: String? = null

    @Volatile
    private var telemetryPausedByUser: Boolean = false

    private var scheduledAutoConnect: Job? = null

    init {
        wsManager.loyaltySyncHandler =
            object : MvpTelemetryLoyaltySyncHandler {
                override suspend fun onLoyaltyAck(correlationId: String, payload: JsonObject) {
                    handleLoyaltyAck(correlationId, payload)
                }

                override suspend fun onStatusChanged(payload: JsonObject) {
                    runCatching {
                        _subscribeInfo.value = LoyaltyWsCodec.decodeStatusChanged(payload)
                    }.onFailure { Timber.w(it, "ViwaTelemetry: loyalty.status.changed decode failed") }
                }

                override suspend fun onPourReportBalanceAck(payload: JsonObject) {
                    mergePourBalanceIntoSubscribeInfo(payload)
                }

                override suspend fun onLoyaltyError(correlationId: String?, code: String, message: String) {
                    Timber.w("ViwaTelemetry: loyalty WS error correlationId=$correlationId code=$code message=$message")
                    if (correlationId != null) {
                        pendingLoyaltyAcks.remove(correlationId)?.complete(
                            Result.failure(LoyaltyPaymentException(code, message)),
                        )
                        when (pendingLoyaltyRequests.remove(correlationId)) {
                            PendingLoyaltyKind.STATUS_GET -> {
                                scope.launch { _invalidLoyaltyCardScans.emit(Unit) }
                            }
                            PendingLoyaltyKind.LEVELS_LIST -> Unit
                            PendingLoyaltyKind.WATER_USE -> Unit
                            PendingLoyaltyKind.PAYMENT_INIT,
                            PendingLoyaltyKind.PAYMENT_STATUS_GET,
                            PendingLoyaltyKind.PAYMENT_COMPLETE,
                            PendingLoyaltyKind.SUBSCRIBE_SALE,
                            PendingLoyaltyKind.SUBSCRIBE_CANCEL,
                            -> Unit
                            null -> Unit
                        }
                    }
                }
            }

        scope.launch {
            telemetryPausedByUser =
                configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) == "true"
            loadTelemetryConfig()
            scheduledAutoConnect =
                launch {
                    delay(3_000)
                    scheduledAutoConnect = null
                    startTelemetryIfRegistered("холодный старт")
                }
        }
    }

    private fun mergePourBalanceIntoSubscribeInfo(payload: JsonObject) {
        _subscribeInfo.update { current ->
            LoyaltyWsCodec.mergePourBalanceAck(current, payload) ?: current
        }
    }

    /** Optimistic UI debit until pour-report ACK reconciles with server balance. */
    fun applyOptimisticSubscriptionPourDeduction(pouredMl: Int) {
        if (pouredMl <= 0) return
        _subscribeInfo.update { current ->
            if (current == null) return@update null
            val remaining = (current.volumeMl - pouredMl).coerceAtLeast(0)
            Timber.d(
                "ViwaTelemetry: optimistic subscription pour -%d ml remainingMl=%d",
                pouredMl,
                remaining,
            )
            current.copy(
                volumeMl = remaining,
                isActiveSubscribe = remaining > 0 && current.isActiveSubscribe,
            )
        }
    }

    private fun handleLoyaltyAck(correlationId: String, payload: JsonObject) {
        pendingLoyaltyAcks.remove(correlationId)?.complete(Result.success(payload))
        when (pendingLoyaltyRequests.remove(correlationId)) {
            PendingLoyaltyKind.STATUS_GET ->
                runCatching {
                    _subscribeInfo.value = LoyaltyWsCodec.decodeStatusAck(payload)
                }.onFailure { Timber.w(it, "ViwaTelemetry: status ack decode failed") }

            PendingLoyaltyKind.LEVELS_LIST ->
                runCatching {
                    _subscriptionLevels.value = LoyaltyWsCodec.decodeLevelsAck(payload)
                }.onFailure { Timber.w(it, "ViwaTelemetry: levels ack decode failed") }

            PendingLoyaltyKind.WATER_USE -> {
                runCatching {
                    val status = LoyaltyWsCodec.decodeStatusAck(payload)
                    _subscribeInfo.value = status
                }.onFailure {
                    lastStatusClientId?.let { clientId ->
                        scope.launch { sendStatusGet(clientId) }
                    }
                }
            }

            PendingLoyaltyKind.PAYMENT_INIT,
            PendingLoyaltyKind.PAYMENT_STATUS_GET,
            PendingLoyaltyKind.PAYMENT_COMPLETE,
            -> Unit

            PendingLoyaltyKind.SUBSCRIBE_SALE ->
                runCatching {
                    _subscribeInfo.value = LoyaltyWsCodec.decodeStatusAck(payload)
                }.onFailure { Timber.w(it, "ViwaTelemetry: subscribe.sale ack decode failed") }

            PendingLoyaltyKind.SUBSCRIBE_CANCEL ->
                runCatching {
                    _subscribeInfo.value = LoyaltyWsCodec.decodeStatusAck(payload)
                }.onFailure { Timber.w(it, "ViwaTelemetry: subscribe.cancel ack decode failed") }

            null -> Timber.d("ViwaTelemetry: orphan loyalty ack correlationId=$correlationId")
        }
    }

    private suspend fun sendLoyaltyRequest(
        type: String,
        payload: JsonObject,
        kind: PendingLoyaltyKind,
    ): Result<JsonObject> {
        val messageId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result<JsonObject>>()
        pendingLoyaltyRequests[messageId] = kind
        pendingLoyaltyAcks[messageId] = deferred
        return wsManager
            .sendEnvelope(type = type, payload = payload, messageId = messageId)
            .map { messageId }
            .recoverCatching { error ->
                pendingLoyaltyRequests.remove(messageId)
                pendingLoyaltyAcks.remove(messageId)
                throw error
            }.fold(
                onSuccess = { deferred.await() },
                onFailure = { Result.failure(it) },
            )
    }

    suspend fun loadTelemetryConfig(): TelemetryConfig = mvpCoordinator.loadTelemetryConfig()

    suspend fun saveTelemetryConfig(config: TelemetryConfig) = mvpCoordinator.saveTelemetryConfig(config)

    suspend fun loadMachineRegistration(): MachineRegistration = mvpCoordinator.loadMachineRegistration()

    suspend fun saveMachineRegistration(reg: MachineRegistration) = mvpCoordinator.saveMachineRegistration(reg)

    suspend fun registerMachine(
        regKey: String,
        serialNumber: String,
        rebind: Boolean = false,
    ): Result<Unit> {
        val normalizedKey = RegistrationKeyUtils.normalize(regKey)
        return if (RegistrationKeyUtils.isValid(normalizedKey)) {
            mvpCoordinator.registerMachine(normalizedKey, serialNumber)
        } else {
            mvpCoordinator.enrollMachine(serialNumber, rebind)
        }
    }

    suspend fun provisionMachine(): Result<Unit> = mvpCoordinator.provisionMachine()

    suspend fun reserveFreeSerial(): Result<String> = mvpCoordinator.reserveFreeSerial()

    suspend fun applyUiSerialChange(uiSerial: String): Boolean = mvpCoordinator.applyUiSerialChange(uiSerial)

    suspend fun connectWithGuard(uiSerial: String): Result<Unit> = mvpCoordinator.connectWithGuard(uiSerial)

    fun connect() {
        scope.launch {
            setTelemetryPausedByUser(false)
            scheduledAutoConnect?.cancel()
            scheduledAutoConnect = null
            mvpCoordinator.connectAuto()
        }
    }

    fun disconnect() {
        scheduledAutoConnect?.cancel()
        scheduledAutoConnect = null
        scope.launch {
            setTelemetryPausedByUser(true)
            mvpCoordinator.disconnect()
        }
    }

    fun reconnect() {
        scope.launch {
            setTelemetryPausedByUser(false)
            scheduledAutoConnect?.cancel()
            scheduledAutoConnect = null
            mvpCoordinator.reconnect()
        }
    }

    private suspend fun startTelemetryIfRegistered(reason: String) {
        if (telemetryPausedByUser) {
            Timber.d("ViwaTelemetry: автоподключение пропущено ($reason) — пауза по запросу пользователя")
            return
        }
        mvpCoordinator.connect()
    }

    private suspend fun setTelemetryPausedByUser(paused: Boolean) {
        telemetryPausedByUser = paused
        configRepository.set(
            JsonStoreKeys.TELEMETRY_PAUSED_BY_USER,
            if (paused) "true" else "false",
        )
    }

    /** Legacy Shaker topic — удалён; температура позже через MVP heartbeat. */
    suspend fun sendSetMachineInfo(temperature0: Int, temperature1: Int): Result<Unit> = Result.success(Unit)

    /** Legacy saleImportTopic — удалён. */
    suspend fun sendSaleImportTopic(items: List<SaleImportItem>): Result<Unit> = Result.success(Unit)

    /** UC-5: `loyalty.status.get` envelope v2. */
    suspend fun sendStatusGet(clientId: String): Result<Unit> {
        val id = clientId.trim()
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("clientId is blank"))
        lastStatusClientId = id
        val messageId = UUID.randomUUID().toString()
        pendingLoyaltyRequests[messageId] = PendingLoyaltyKind.STATUS_GET
        return wsManager
            .sendEnvelope(
                type = LoyaltyWsCodec.TYPE_STATUS_GET,
                payload = LoyaltyWsCodec.encodeStatusGet(id),
                messageId = messageId,
            ).map { Unit }
            .onFailure { pendingLoyaltyRequests.remove(messageId) }
    }

    /** Backward-compatible alias for legacy callers. */
    suspend fun sendStatusSubscribeTopic(userUuid: String): Result<Unit> = sendStatusGet(userUuid)

    /** UC-7 prep: `loyalty.levels.list` global tier catalog. */
    suspend fun sendSubscriptionLevelRequest(): Result<Unit> {
        val messageId = UUID.randomUUID().toString()
        pendingLoyaltyRequests[messageId] = PendingLoyaltyKind.LEVELS_LIST
        return wsManager
            .sendEnvelope(
                type = LoyaltyWsCodec.TYPE_LEVELS_LIST,
                payload = LoyaltyWsCodec.encodeLevelsList(),
                messageId = messageId,
            ).map { Unit }
            .onFailure { pendingLoyaltyRequests.remove(messageId) }
    }

    fun onLoyaltyCardScanned(clientUuid: String) {
        val id = clientUuid.trim()
        if (id.isEmpty()) return
        scope.launch {
            _subscriptionLevels.value = null
            _loyaltyCardClientScans.emit(id)
            val connected = connectionState.value is ConnectionState.Connected
            if (connected) {
                sendStatusGet(id)
                sendSubscriptionLevelRequest()
                Timber.d("ViwaTelemetry: loyalty scan $id → status.get + levels.list")
            } else {
                handleOfflineLoyaltyCardScan(id)
            }
        }
    }

    private suspend fun handleOfflineLoyaltyCardScan(clientUuid: String) {
        val machineId = loadMachineRegistration().machineId
        if (machineId.isBlank()) {
            _offlineLoyaltyDenyReason.emit(OfflineAuthorizationReason.OFFLINE_NO_GRANT)
            return
        }
        val grantInfo = offlinePourAuthorizationService.buildOfflineSubscribeInfo(clientUuid, machineId)
        if (grantInfo == null) {
            val probe = offlinePourAuthorizationService.authorizePour(clientUuid, machineId, volumeMl = 1)
            _offlineLoyaltyDenyReason.emit(probe.reason)
            return
        }
        _subscribeInfo.value =
            SubscribeInformationState(
                isStatusRequest = true,
                isActiveSubscribe = grantInfo.dailyRemainingMl > 0,
                clientId = clientUuid,
                subscribeDateEnd = java.time.Instant.ofEpochMilli(grantInfo.expiresAtMs).toString(),
                volumeMl = grantInfo.dailyRemainingMl,
                maxVolumeMl = grantInfo.dailyLimitMl,
            )
        Timber.d("ViwaTelemetry: offline loyalty scan authorized remainingMl=%d", grantInfo.dailyRemainingMl)
    }

    fun onInvalidLoyaltyCardScan() {
        scope.launch { _invalidLoyaltyCardScans.emit(Unit) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearSubscribeUiState() {
        _subscribeInfo.value = null
        _subscriptionLevels.value = null
        _loyaltyCardClientScans.resetReplayCache()
        lastStatusClientId = null
    }

    /** UC-7: `loyalty.payment.init` — create server-side SubscriptionPayment. */
    suspend fun sendPaymentInit(params: SubscriptionPaymentInitParams): Result<SubscriptionPaymentInit> =
        sendLoyaltyRequest(
            type = LoyaltyWsCodec.TYPE_PAYMENT_INIT,
            payload = LoyaltyWsCodec.encodePaymentInit(params),
            kind = PendingLoyaltyKind.PAYMENT_INIT,
        ).mapCatching { payload -> LoyaltyWsCodec.decodePaymentInitAck(payload) }

    /** UC-7: poll SBP payment until PAID/FAILED/EXPIRED. */
    suspend fun sendPaymentStatusGet(paymentId: String): Result<SubscriptionPaymentStatusResult> =
        sendLoyaltyRequest(
            type = LoyaltyWsCodec.TYPE_PAYMENT_STATUS_GET,
            payload = LoyaltyWsCodec.encodePaymentStatusGet(paymentId),
            kind = PendingLoyaltyKind.PAYMENT_STATUS_GET,
        ).mapCatching { payload -> LoyaltyWsCodec.decodePaymentStatusAck(payload) }

    /** UC-7: confirm CARD POS success on server. */
    suspend fun sendPaymentComplete(
        paymentId: String,
        requestUuid: String,
        externalRef: String?,
    ): Result<SubscriptionPaymentStatusResult> =
        sendLoyaltyRequest(
            type = LoyaltyWsCodec.TYPE_PAYMENT_COMPLETE,
            payload = LoyaltyWsCodec.encodePaymentComplete(paymentId, requestUuid, externalRef),
            kind = PendingLoyaltyKind.PAYMENT_COMPLETE,
        ).mapCatching { payload -> LoyaltyWsCodec.decodePaymentStatusAck(payload) }

    /** UC-7: apply subscription only when payment is PAID on server. */
    suspend fun sendSubscribeSale(params: SubscriptionSaleParams): Result<Unit> {
        lastStatusClientId = params.clientId
        return sendLoyaltyRequest(
            type = LoyaltyWsCodec.TYPE_SUBSCRIBE_SALE,
            payload = LoyaltyWsCodec.encodeSubscribeSale(params),
            kind = PendingLoyaltyKind.SUBSCRIBE_SALE,
        ).map { Unit }
    }

    /** UC-7: cancel pending subscription purchase. */
    suspend fun sendSubscribeCancel(clientId: String, requestUuid: String): Result<Unit> {
        lastStatusClientId = clientId
        return sendLoyaltyRequest(
            type = LoyaltyWsCodec.TYPE_SUBSCRIBE_CANCEL,
            payload = LoyaltyWsCodec.encodeSubscribeCancel(clientId, requestUuid),
            kind = PendingLoyaltyKind.SUBSCRIBE_CANCEL,
        ).map { Unit }
    }

    /** Legacy saleSubscribeTopic — routes to `loyalty.subscribe.sale` when paymentId present. */
    suspend fun sendSaleSubscribeTopic(body: SaleSubscribeTopicBody): Result<Unit> {
        Timber.w("ViwaTelemetry: sendSaleSubscribeTopic legacy path — use sendSubscribeSale with paymentId")
        return Result.failure(IllegalStateException("Legacy saleSubscribeTopic without paymentId is forbidden"))
    }

    fun startSubscriptionSaleTimer(
        requestUuid: String,
        machineClientId: String,
        userUuid: String,
        machineId: Int,
    ) {
        clearSubscriptionSaleTimer(requestUuid)
        subscriptionSaleTimers[requestUuid] =
            scope.launch {
                delay(SUBSCRIPTION_SALE_TIMEOUT_MS)
                runCatching { sendSubscribeCancel(userUuid, requestUuid) }
                    .onFailure { Timber.w(it, "ViwaTelemetry: subscribe.cancel on timer failed") }
            }
    }

    fun clearSubscriptionSaleTimer(requestUuid: String) {
        subscriptionSaleTimers.remove(requestUuid)?.cancel()
    }

    /** Legacy API → telemetry v3 `telemetry.pour.report` (plain water, measured ml). */
    suspend fun sendWaterUse(request: LoyaltyWaterUseRequest): Result<Unit> {
        if (!sentWaterUseRequestUuids.add(request.requestUuid)) {
            Timber.d("ViwaTelemetry: pour.report deduplicated requestUuid=${request.requestUuid}")
            return Result.success(Unit)
        }
        lastStatusClientId = request.clientId
        val pour =
            PourEventSnapshot(
                requestUuid = request.requestUuid,
                pouredAt = TelemetryIsoTimestamps.nowUtc(),
                volumeMl = request.volumeMl,
                pourKind = PourKind.PLAIN_WATER.wireValue,
                clientId = request.clientId,
                plainWaterType = PlainWaterType.FILTERED.wireValue,
            )
        dispenseSyncCoordinator.enqueuePourReport(pour)
        return Result.success(Unit)
    }

    /** Backward-compatible wrapper mapping legacy body → telemetry.pour.report. */
    suspend fun sendUseSubscriptionSaleTopic(body: UseSubscriptionSaleBody): Result<Unit> {
        val volumeMl = (body.volume * 1000).toInt().coerceAtLeast(1)
        return sendWaterUse(
            LoyaltyWaterUseRequest(
                clientId = body.clientId,
                requestUuid = body.requestUuid,
                volumeMl = volumeMl,
                ingredientId = body.ingredientId,
                isFree = body.isFree,
                priceKopecks = (body.price * 100).toInt().coerceAtLeast(0),
            ),
        )
    }
}
