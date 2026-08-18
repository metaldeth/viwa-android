package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.BuildConfig
import com.viwa.android.data.network.NetworkTrafficChannel
import com.viwa.android.data.network.NetworkTrafficDirection
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.network.redactNetworkPayload
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.remote.telemetry.mvp.cells.CellsContentReportAckAwaiter
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_COMMAND
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_SYNC_CONTROL
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.RecipeOutboxStore
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import com.viwa.android.di.AppIoScope
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * WebSocket simple-telemetry MVP: JWT auth, hello → ONLINE, heartbeat с ack, RFC6455 ping/pong.
 * Phase 1: session generation fencing, explicit FSM transitions, coordinated liveness detectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MvpTelemetryWebSocketManager
@Inject
constructor(
    @AppIoScope private val appScope: CoroutineScope,
    private val networkTrafficLogger: NetworkTrafficLogger,
    private val ackRouter: TelemetryAckRouter,
    private val cellsContentReportAckAwaiter: CellsContentReportAckAwaiter,
    private val recipeSyncCoordinator: RecipeSyncCoordinator,
    private val outboxDrainCoordinator: MachineOutboxDrainCoordinator,
    private val outboxStore: MachineOutboxStore,
    private val recipeOutboxStore: RecipeOutboxStore,
    private val recipeMessageCodec: RecipeMessageCodec,
    private val offlineEntitlementCoordinator: com.viwa.android.data.remote.telemetry.mvp.offline.OfflineEntitlementSessionCoordinator,
    private val technicianKeySessionCoordinator: com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeySessionCoordinator,
    private val logShipCoordinator: LogShipCoordinator,
    private val appUpdateCoordinatorProvider: javax.inject.Provider<com.viwa.android.domain.ota.AppUpdateCoordinator>,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val fsm = TelemetryConnectionFsm()
    private val reconnectRandom = Random.Default

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var connectJob: Job? = null
    private var activeClient: MvpWsClient? = null
    private var heartbeatJob: Job? = null
    private var helloTimeoutJob: Job? = null
    private var heartbeatWatchdogJob: Job? = null
    private var helloReceived = false
    private var heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SEC
    private var authFailure = false
    private var temperatureProvider: () -> Double? = { null }
    private var lastHeartbeatMessageId: String? = null
    @Volatile private var lastHeartbeatAckAtMs: Long = 0L
    @Volatile private var networkValidated = true
    @Volatile private var pendingSupersedeBackoff = false
    @Volatile private var outboxBatchCapability: MvpOutboxBatchCapabilityDto? = null
    @Volatile private var offlineEntitlementCapability: com.viwa.android.data.remote.telemetry.mvp.offline.MvpOfflineEntitlementCapabilityDto? = null
    @Volatile private var technicianKeysCapability: com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto? = null
    @Volatile private var logShipCapability: MvpLogShipCapabilityDto? = null
    @Volatile private var serverTechnicianKeysEnabled: Boolean? = null
    private val heartbeatTrafficLogCounter = AtomicInteger(0)
    private val transportPingLogCounter = AtomicInteger(0)

    /** Делегат cells sync; wiring из [SimpleTelemetryCoordinator]. */
    var cellsSyncHandler: MvpTelemetryCellsSyncHandler? = null

    /** Делегат loyalty WS; wiring из [ViwaTelemetryService]. */
    var loyaltySyncHandler: MvpTelemetryLoyaltySyncHandler? = null

    /** Делегат technician.key.validate ack/error; wiring из [TechnicianKeyOnlineValidator]. */
    var technicianKeySyncHandler: com.viwa.android.domain.technician.MvpTelemetryTechnicianKeySyncHandler? = null

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 10
        const val AUTH_CLOSE_CODE = 4401
        const val SUPERSEDE_CLOSE_CODE = 4001
        const val HEARTBEAT_TRAFFIC_LOG_EVERY_N = 20
        const val TRANSPORT_PING_LOG_EVERY_N = 10
        const val CONNECTION_LOST_TIMEOUT_SEC = 18
        const val HELLO_TIMEOUT_MS = 15_000L
        const val HEARTBEAT_ACK_GRACE_SEC = 5
        const val HEARTBEAT_WATCHDOG_CHECK_INTERVAL_MS = 1_000L
        const val SHIPPED_WS_LOG_TAG = "MvpTelemetry WS"
    }

    fun reportAuthFailure(message: String) {
        authFailure = true
        transitionFsm(TelemetryConnectionPhase.AuthError, "auth failure: $message")
        _connectionState.value = ConnectionState.Error(message)
    }

    fun connect(
        wsUrl: String,
        tokenProvider: suspend () -> String?,
        temperatureProvider: () -> Double? = { null },
        onAuthFailure: () -> Unit = {},
    ) {
        this.temperatureProvider = temperatureProvider
        connectJob?.cancel()
        connectJob =
            appScope.launch {
                lifecycleMutex.withLock {
                    authFailure = false
                    fsm.resetBackoff()
                    var attempt = 0
                    while (isActive) {
                        helloReceived = false
                        cancelLivenessJobs()
                        resetHeartbeatAckTracking()
                        val sessionGeneration = fsm.nextSessionGeneration()
                        transitionFsm(TelemetryConnectionPhase.Connecting, "connect attempt")
                        _connectionState.value = ConnectionState.Connecting
                        logSystem("MVP WS: подключение $wsUrl gen=$sessionGeneration")

                        val useSupersedeBackoff = pendingSupersedeBackoff
                        pendingSupersedeBackoff = false
                        val delayMs =
                            TelemetryReconnectBackoff.delayMs(
                                attempt = attempt,
                                supersededFlatBackoff = useSupersedeBackoff,
                                random = reconnectRandom,
                            )
                        if (attempt > 0 || useSupersedeBackoff) {
                            transitionFsm(TelemetryConnectionPhase.Backoff, "backoff ${delayMs}ms")
                            _connectionState.value = ConnectionState.Disconnected(delayMs)
                            delay(delayMs)
                        }

                        val bearerToken =
                            runCatching { tokenProvider() }.getOrElse {
                                Timber.w(it, "MvpTelemetry WS token provider failed")
                                null
                            }
                        if (bearerToken.isNullOrBlank()) {
                            authFailure = true
                            transitionFsm(TelemetryConnectionPhase.AuthError, "JWT unavailable")
                            if (_connectionState.value !is ConnectionState.Error) {
                                _connectionState.value = ConnectionState.Error("Не удалось получить JWT")
                            }
                            onAuthFailure()
                            fsm.incrementBackoff()
                            attempt = fsm.backoffAttempt
                            authFailure = false
                            continue
                        }

                        try {
                            suspendCancellableCoroutine { cont ->
                                cont.invokeOnCancellation { activeClient?.close() }

                                lateinit var client: MvpWsClient
                                client =
                                    MvpWsClient(
                                        sessionGeneration = sessionGeneration,
                                        uri = URI.create(wsUrl),
                                        bearer = "Bearer $bearerToken",
                                        onOpenCallback = { handshake ->
                                            if (!acceptSession(client, sessionGeneration, "onOpen")) return@MvpWsClient
                                            if (handshake.httpStatus == 401.toShort() || handshake.httpStatus == 403.toShort()) {
                                                authFailure = true
                                                transitionFsm(
                                                    TelemetryConnectionPhase.AuthError,
                                                    "HTTP ${handshake.httpStatus}",
                                                )
                                                _connectionState.value =
                                                    ConnectionState.Error(
                                                        "Ошибка авторизации WS (HTTP ${handshake.httpStatus})",
                                                    )
                                                logSystem("MVP WS: auth failure HTTP ${handshake.httpStatus}")
                                                onAuthFailure()
                                                if (cont.isActive) cont.resume(Unit) {}
                                                return@MvpWsClient
                                            }
                                            transitionFsm(TelemetryConnectionPhase.AwaitingHello, "socket open")
                                            logSystem("MVP WS: сокет открыт HTTP ${handshake.httpStatus} gen=$sessionGeneration")
                                            startHelloTimeout(client, sessionGeneration)
                                        },
                                        onText = { text -> handleIncoming(text, client, sessionGeneration) },
                                        onClosed = { code, reason ->
                                            if (!acceptSession(client, sessionGeneration, "onClosed")) {
                                                if (cont.isActive) cont.resume(Unit) {}
                                                return@MvpWsClient
                                            }
                                            logSystem(
                                                "MVP WS: closed code=$code reason='$reason' " +
                                                    "gen=$sessionGeneration phase=${fsm.phase} " +
                                                    "helloReceived=$helloReceived networkDegraded=${!networkValidated}",
                                            )
                                            when (code) {
                                                AUTH_CLOSE_CODE, 1008, 1002 -> {
                                                    authFailure = true
                                                    transitionFsm(
                                                        TelemetryConnectionPhase.AuthError,
                                                        "close $code $reason",
                                                    )
                                                    _connectionState.value =
                                                        ConnectionState.Error("Ошибка авторизации WS: $reason")
                                                    onAuthFailure()
                                                }
                                                SUPERSEDE_CLOSE_CODE -> {
                                                    pendingSupersedeBackoff = true
                                                    fsm.bumpGenerationForSupersede("4001 $reason")
                                                    logFsmStructured(
                                                        fsm.transitions.lastOrNull()
                                                            ?: return@MvpWsClient,
                                                    )
                                                    helloReceived = false
                                                    cancelLivenessJobs()
                                                }
                                                else -> Unit
                                            }
                                            if (cont.isActive) cont.resume(Unit) {}
                                        },
                                        onErrorCallback = {
                                            if (!acceptSession(client, sessionGeneration, "onError")) {
                                                if (cont.isActive) cont.resume(Unit) {}
                                                return@MvpWsClient
                                            }
                                            logSystem(
                                                "MVP WS: socket error gen=$sessionGeneration " +
                                                    "phase=${fsm.phase} helloReceived=$helloReceived " +
                                                    "networkDegraded=${!networkValidated}",
                                            )
                                            if (helloReceived && !authFailure) {
                                                transitionFsm(TelemetryConnectionPhase.Backoff, "socket error")
                                                _connectionState.value = ConnectionState.Disconnected()
                                            }
                                            if (cont.isActive) cont.resume(Unit) {}
                                        },
                                        onTransportPing = {
                                            if (acceptSession(client, sessionGeneration, "transportPing")) {
                                                logTransportPing()
                                            }
                                        },
                                        onTransportPong = {
                                            if (acceptSession(client, sessionGeneration, "transportPong")) {
                                                logTransportPong()
                                            }
                                        },
                                    )
                                activeClient = client
                                client.connect()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "MvpTelemetry WS connect exception")
                        }

                        if (activeClient?.sessionGeneration == sessionGeneration) {
                            activeClient?.close()
                            activeClient = null
                        }
                        heartbeatJob?.cancel()
                        cancelLivenessJobs()

                        if (authFailure) {
                            // Auth/JWT может восстановиться без смены сети. Инвалидируем токен через
                            // callback и остаёмся в пожизненном reconnect-loop с capped backoff.
                            authFailure = false
                            fsm.incrementBackoff()
                            attempt = fsm.backoffAttempt
                        } else if (helloReceived) {
                            fsm.resetBackoff()
                            attempt = 0
                        } else {
                            fsm.incrementBackoff()
                            attempt = fsm.backoffAttempt
                        }

                        val nextDelay =
                            TelemetryReconnectBackoff.delayMs(
                                attempt = attempt,
                                random = reconnectRandom,
                            )
                        transitionFsm(TelemetryConnectionPhase.Backoff, "await reconnect")
                        _connectionState.value = ConnectionState.Disconnected(nextDelay)
                    }
                }
            }
    }

    fun disconnect() {
        logSystem("MVP WS: отключение по запросу")
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        cancelLivenessJobs()
        activeClient?.close()
        activeClient = null
        helloReceived = false
        authFailure = false
        pendingSupersedeBackoff = false
        networkValidated = true
        outboxBatchCapability = null
        offlineEntitlementCapability = null
        technicianKeysCapability = null
        logShipCapability = null
        serverTechnicianKeysEnabled = null
        outboxDrainCoordinator.stopPeriodicFlush()
        technicianKeySessionCoordinator.onDisconnect()
        logShipCoordinator.onDisconnect()
        cellsContentReportAckAwaiter.cancelAll()
        appScope.launch {
            runCatching { cellsSyncHandler?.onRecipeDisconnect() }
                .onFailure { Timber.w(it, "MvpTelemetry WS: recipe disconnect cleanup failed") }
        }
        resetHeartbeatAckTracking()
        heartbeatTrafficLogCounter.set(0)
        transportPingLogCounter.set(0)
        fsm.resetToIdle()
        transitionFsm(TelemetryConnectionPhase.Idle, "disconnect")
        _connectionState.value = ConnectionState.Disconnected()
    }

    /** Network lost — do not force-close; watchdog / transport timeout handles half-open. */
    fun notifyNetworkDegraded() {
        if (!networkValidated) return
        networkValidated = false
        logSystem("MVP WS: network degraded — awaiting watchdog (gen=${fsm.sessionGeneration})")
    }

    /** Marks validated network; side effects are debounced in [TelemetryNetworkValidatedSideEffectsCoordinator]. */
    fun notifyNetworkValidated() {
        networkValidated = true
    }

    /**
     * True while [connectJob] is running through connect / hello-wait / backoff retry lifecycle.
     * Network-validated must not reset this with a fresh [connect] call.
     */
    fun hasActiveConnectLifecycle(): Boolean {
        if (connectJob?.isActive != true) return false
        return when (fsm.phase) {
            TelemetryConnectionPhase.Connecting,
            TelemetryConnectionPhase.AwaitingHello,
            TelemetryConnectionPhase.Backoff,
            TelemetryConnectionPhase.AwaitingNetwork,
            TelemetryConnectionPhase.AuthError,
            -> true
            else -> false
        }
    }

    /**
     * Whether a network-validated event may start a new WS connect from coordinator.
     * Preserves half-open [Active] sessions and in-flight reconnect/backoff loops.
     */
    fun shouldInitiateConnectOnNetworkValidated(): Boolean {
        if (fsm.phase == TelemetryConnectionPhase.Active) return false
        if (hasActiveConnectLifecycle()) return false
        return when (fsm.phase) {
            TelemetryConnectionPhase.Idle,
            TelemetryConnectionPhase.AwaitingNetwork,
            TelemetryConnectionPhase.AuthError,
            -> true
            else -> false
        }
    }

    fun isNetworkValidated(): Boolean = networkValidated

    fun outboxBatchCapability(): MvpOutboxBatchCapabilityDto? = outboxBatchCapability

    fun offlineEntitlementCapability(): com.viwa.android.data.remote.telemetry.mvp.offline.MvpOfflineEntitlementCapabilityDto? =
        offlineEntitlementCapability

    fun technicianKeysCapability(): com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto? =
        technicianKeysCapability

    fun logShipCapability(): MvpLogShipCapabilityDto? = logShipCapability

    fun serverTechnicianKeysEnabled(): Boolean? = serverTechnicianKeysEnabled

    suspend fun sendEnvelope(
        type: String,
        payload: JsonObject,
        messageId: String = java.util.UUID.randomUUID().toString(),
    ): Result<String> =
        runCatching {
            val client = activeClient ?: error("WebSocket not connected")
            if (!helloReceived) error("WebSocket not online (hello pending)")
            if (client.readyState != ReadyState.OPEN) error("WebSocket not open")
            val envelope = MvpWsEnvelopeFactory.create(type = type, payload = payload, messageId = messageId)
            val raw = json.encodeToString(MvpWsEnvelopeDto.serializer(), envelope)
            client.send(raw)
            logOut(raw, wsType = type)
            messageId
        }

    /** Test seam — deliver inbound without a live socket. */
    internal fun deliverInboundForTests(
        sessionGeneration: Long,
        client: MvpWsClient,
        text: String,
    ) {
        handleIncoming(text, client, sessionGeneration)
    }

    internal fun acceptInboundSession(
        sourceClient: MvpWsClient,
        sourceGeneration: Long,
        event: String,
    ): Boolean = acceptSession(sourceClient, sourceGeneration, event)

    internal fun currentSessionGeneration(): Long = fsm.sessionGeneration

    internal fun fsmPhase(): TelemetryConnectionPhase = fsm.phase

    internal fun fsmTransitions(): List<FsmTransition> = fsm.transitions

    internal fun bindActiveSessionForTests(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        activeClient = client
        fsm.assignSessionGenerationForTests(sessionGeneration)
    }

    internal fun createDetachedClientForTests(sessionGeneration: Long): MvpWsClient =
        MvpWsClient(
            sessionGeneration = sessionGeneration,
            uri = URI.create("ws://127.0.0.1:9/test"),
            bearer = "Bearer test",
            onOpenCallback = {},
            onText = {},
            onClosed = { _, _ -> },
            onErrorCallback = {},
            onTransportPing = {},
            onTransportPong = {},
        )

    internal fun startHelloTimeoutForTests(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        startHelloTimeout(client, sessionGeneration)
    }

    internal fun startHeartbeatWatchdogForTests(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        heartbeatJob?.cancel()
        helloReceived = true
        lastHeartbeatAckAtMs = System.currentTimeMillis() - 30_000L
        heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SEC
        startHeartbeatWatchdog(client, sessionGeneration)
    }

    private fun acceptSession(
        sourceClient: MvpWsClient,
        sourceGeneration: Long,
        event: String,
    ): Boolean {
        val accepted =
            WsSessionFence.accept(
                sourceClient = sourceClient,
                sourceGeneration = sourceGeneration,
                activeClient = activeClient,
                activeGeneration = fsm.sessionGeneration,
            )
        if (!accepted) {
            logSystem(
                WsSessionFence.dropReason(
                    event = event,
                    sourceGeneration = sourceGeneration,
                    activeGeneration = fsm.sessionGeneration,
                    sameClient = sourceClient === activeClient,
                ),
            )
        }
        return accepted
    }

    private fun handleIncoming(
        text: String,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "inbound")) return
        val wsType = extractWsType(text)
        logIn(text, wsType = wsType)
        runCatching {
            val envelope = json.decodeFromString(MvpWsEnvelopeDto.serializer(), text)
            when (envelope.type) {
                "hello" -> onHello(envelope, sourceClient, sessionGeneration)
                "ack" -> onAck(envelope, sourceClient, sessionGeneration)
                "error" -> onError(envelope, sourceClient, sessionGeneration)
                "cells.snapshot" -> onCellsSnapshot(envelope, sourceClient, sessionGeneration)
                RECIPE_WS_TYPE_SYNC_CONTROL ->
                    onRecipeSyncControl(envelope, sourceClient, sessionGeneration)
                RECIPE_WS_TYPE_COMMAND ->
                    onRecipeCommand(envelope, sourceClient, sessionGeneration)
                LoyaltyWsCodec.TYPE_STATUS_CHANGED ->
                    onLoyaltyStatusChanged(envelope, sourceClient, sessionGeneration)
                else -> {
                    if (recipeSyncCoordinator.isRecipeWireType(envelope.type)) {
                        Timber.d("MvpTelemetry WS: ignored recipe type=${envelope.type} (not negotiated)")
                    } else {
                        Timber.d("MvpTelemetry WS: ignored type=${envelope.type}")
                    }
                }
            }
        }.onFailure { Timber.w(it, "MvpTelemetry WS parse failed") }
    }

    private fun onHello(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "hello")) return
        val payloadEl = envelope.payload ?: return
        val hello = json.decodeFromJsonElement(MvpHelloPayloadDto.serializer(), payloadEl)
        heartbeatIntervalSeconds = hello.heartbeatIntervalSeconds.coerceAtLeast(5)
        outboxBatchCapability = hello.capabilities?.outboxBatch
        offlineEntitlementCapability = hello.capabilities?.offlineEntitlement
        serverTechnicianKeysEnabled = hello.featureFlags?.technicianKeys
        technicianKeysCapability =
            if (
                com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS &&
                hello.featureFlags?.technicianKeys == true
            ) {
                hello.capabilities?.technicianKeys
            } else {
                null
            }
        logShipCapability =
            if (com.viwa.android.logging.LogShipFeatureFlags.FEATURE_LOG_SHIP) {
                hello.capabilities?.logShip
            } else {
                null
            }
        helloReceived = true
        helloTimeoutJob?.cancel()
        lastHeartbeatAckAtMs = System.currentTimeMillis()
        transitionFsm(TelemetryConnectionPhase.Active, "hello received")
        _connectionState.value = ConnectionState.Connected
        logSystem(
            "MVP WS: ONLINE serial=${hello.serialNumber}, heartbeat=${heartbeatIntervalSeconds}s gen=$sessionGeneration",
        )
        startHeartbeatLoop(sourceClient, sessionGeneration)
        startHeartbeatWatchdog(sourceClient, sessionGeneration)
        appScope.launch {
            runCatching { cellsSyncHandler?.onWebSocketHello(hello) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: cells sync onHello failed") }
            runCatching { offlineEntitlementCoordinator.onHello(hello) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: offline entitlement onHello failed") }
            runCatching { technicianKeySessionCoordinator.onHello(hello) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: technician keys onHello failed") }
            runCatching { logShipCoordinator.onHello(hello.capabilities?.logShip) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: log ship onHello failed") }
            runCatching {
                appUpdateCoordinatorProvider.get().onHello(
                    appUpdatesEnabled = hello.featureFlags?.appUpdates,
                    otaSigningPublicKeys = hello.otaSigningPublicKeys,
                )
            }.onFailure { Timber.w(it, "MvpTelemetry WS: OTA onHello failed") }
        }
    }

    private fun onAck(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "ack")) return
        val correlation =
            envelope.correlationId
                ?: envelope.payload?.jsonObject?.get("correlationId")?.jsonPrimitive?.content
        val pendingHeartbeatId = lastHeartbeatMessageId
        if (!correlation.isNullOrBlank() && correlation == pendingHeartbeatId) {
            lastHeartbeatAckAtMs = System.currentTimeMillis()
            return
        }
        Timber.d("MvpTelemetry WS: ack correlationId=$correlation gen=$sessionGeneration")
        val payload = envelope.payload?.jsonObject ?: return
        appScope.launch {
            runCatching {
                val outcome =
                    ackRouter.routeAck(
                        envelope = envelope,
                        sessionGeneration = sessionGeneration,
                        cellsHandler = { ackPayload ->
                            cellsSyncHandler?.onSchemaAck(ackPayload)
                        },
                        loyaltyHandler = { corr, ackPayload ->
                            loyaltySyncHandler?.onLoyaltyAck(corr, ackPayload)
                        },
                        technicianHandler = { corr, ackPayload ->
                            technicianKeySyncHandler?.onValidateAck(corr, ackPayload)
                        },
                        pourBalanceHandler = { ackPayload ->
                            loyaltySyncHandler?.onPourReportBalanceAck(ackPayload)
                        },
                        cellsContentAckHandler = { correlation, ackPayload ->
                            cellsContentReportAckAwaiter.completeAck(correlation, ackPayload)
                        },
                        recipeAckHandler = { correlation, ackPayload ->
                            onRecipeAck(correlation, ackPayload)
                        },
                        onUnprovenPourDedupAck = { entry ->
                            outboxDrainCoordinator.handleUnprovenPourDedupAck(entry)
                        },
                    )
                if (outcome == AckRouteOutcome.UNPROVEN_POUR_DEDUP) {
                    Timber.w(
                        "MvpTelemetry WS: unproven pour dedup ack correlationId=$correlation gen=$sessionGeneration",
                    )
                }
            }.onFailure { Timber.w(it, "MvpTelemetry WS: ack router failed") }
        }
    }

    private fun onError(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "error")) return
        val correlation = envelope.correlationId
        val payload = envelope.payload?.jsonObject ?: return
        val code = payload["code"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val message = payload["message"]?.jsonPrimitive?.content ?: code
        appScope.launch {
            runCatching {
                val outcome =
                    ackRouter.routeError(
                        envelope = envelope,
                        sessionGeneration = sessionGeneration,
                        outboxErrorHandler = { entry, errCode, errMessage ->
                            outboxDrainCoordinator.handleOutboxError(entry, errCode, errMessage)
                        },
                        loyaltyErrorHandler = { corr, errCode, errMessage ->
                            loyaltySyncHandler?.onLoyaltyError(corr, errCode, errMessage)
                        },
                        technicianErrorHandler = { corr, errCode, errMessage ->
                            technicianKeySyncHandler?.onValidateError(corr, errCode, errMessage)
                        },
                        cellsContentErrorHandler = { corr, errCode, errMessage ->
                            cellsContentReportAckAwaiter.completeError(
                                corr,
                                "$errCode: $errMessage",
                            )
                        },
                    )
                if (outcome == AckRouteOutcome.ORPHAN) {
                    Timber.w(
                        "MvpTelemetry WS: unhandled error frame correlationId=$correlation " +
                            "code=$code message=$message gen=$sessionGeneration",
                    )
                }
            }.onFailure { Timber.w(it, "MvpTelemetry WS: error router failed") }
        }
    }

    private fun onLoyaltyStatusChanged(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "loyalty.status.changed")) return
        val payload = envelope.payload?.jsonObject ?: return
        appScope.launch {
            runCatching { loyaltySyncHandler?.onStatusChanged(payload) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: loyalty.status.changed failed") }
        }
    }

    private fun onCellsSnapshot(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, "cells.snapshot")) return
        val payloadEl = envelope.payload ?: return
        val payloadJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payloadEl)
        appScope.launch {
            runCatching { cellsSyncHandler?.onCellsSnapshot(payloadJson) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: cells.snapshot handler failed") }
        }
    }

    private fun onRecipeSyncControl(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, RECIPE_WS_TYPE_SYNC_CONTROL)) return
        if (!recipeSyncCoordinator.isHelloEligible()) {
            Timber.d("MvpTelemetry WS: ignoring sync.control — recipe sync not negotiated")
            return
        }
        val payloadEl = envelope.payload ?: return
        val payloadJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payloadEl)
        appScope.launch {
            runCatching { cellsSyncHandler?.onRecipeSyncControl(payloadJson) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: recipe sync.control failed") }
        }
    }

    private fun onRecipeCommand(
        envelope: MvpWsEnvelopeDto,
        sourceClient: MvpWsClient,
        sessionGeneration: Long,
    ) {
        if (!acceptSession(sourceClient, sessionGeneration, RECIPE_WS_TYPE_COMMAND)) return
        if (!recipeSyncCoordinator.isHelloEligible()) {
            Timber.d("MvpTelemetry WS: ignoring recipe command — recipe sync not negotiated")
            return
        }
        val payloadEl = envelope.payload ?: return
        val payloadJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payloadEl)
        appScope.launch {
            runCatching { cellsSyncHandler?.onRecipeCommand(payloadJson) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: recipe command failed") }
        }
    }

    private suspend fun onRecipeAck(
        correlation: String,
        payload: JsonObject,
    ) {
        if (!recipeSyncCoordinator.isHelloEligible()) {
            Timber.d("MvpTelemetry WS: ignoring recipe ack correlation=$correlation")
            return
        }
        val entry = outboxStore.findByMessageId(correlation)
        if (entry != null) {
            val kind = MachineOutboxKind.fromWire(entry.kind)
            if (
                kind == MachineOutboxKind.CELLS_RECIPE_REPORT ||
                kind == MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK
            ) {
                if (recipeAckIndicatesSuccess(payload)) {
                    outboxStore.markAcked(messageId = correlation, kind = kind)
                    when (kind) {
                        MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK ->
                            recipeOutboxStore.onCommandAckOutboxDelivered(entry)
                        MachineOutboxKind.CELLS_RECIPE_REPORT ->
                            recipeOutboxStore.onRecipeReportOutboxDelivered(entry)
                        else -> Unit
                    }
                    outboxStore.purgeAckedByMessageIds(listOf(correlation))
                }
                return
            }
        }
        if (recipeMessageCodec.isRecipeCommandAckPayload(payload)) {
            Timber.d("MvpTelemetry WS: recipe command ack batch correlation=$correlation")
            return
        }
        if (recipeMessageCodec.isRecipeReportAckPayload(payload)) {
            Timber.d("MvpTelemetry WS: recipe report ack correlation=$correlation keys=${payload.keys}")
        }
    }

    private fun recipeAckIndicatesSuccess(payload: JsonObject): Boolean {
        if (payload["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) return true
        if (payload["idempotent"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) return true
        if (payload["ingested"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) return true
        if (payload["delivered"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) return true
        val results = payload["results"]?.jsonArray ?: return false
        return results.any { element ->
            val obj = element.jsonObject
            obj["status"]?.jsonPrimitive?.content?.lowercase() in setOf("acked", "idempotent", "applied")
        }
    }

    private fun startHelloTimeout(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        helloTimeoutJob?.cancel()
        helloTimeoutJob =
            appScope.launch {
                delay(HELLO_TIMEOUT_MS)
                if (
                    acceptSession(client, sessionGeneration, "helloTimeout") &&
                    !helloReceived &&
                    !authFailure
                ) {
                    logSystem("MVP WS: hello timeout gen=$sessionGeneration")
                    transitionFsm(TelemetryConnectionPhase.Backoff, "hello timeout")
                    forceClose(client, "hello timeout")
                }
            }
    }

    private fun startHeartbeatLoop(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob =
            appScope.launch {
                while (
                    isActive &&
                    acceptSession(client, sessionGeneration, "heartbeatLoop") &&
                    helloReceived &&
                    client.readyState == ReadyState.OPEN
                ) {
                    delay(heartbeatIntervalSeconds * 1000L)
                    if (!acceptSession(client, sessionGeneration, "heartbeatLoop")) break
                    sendHeartbeat(client, sessionGeneration, temperatureProvider())
                }
                if (
                    isActive &&
                    acceptSession(client, sessionGeneration, "heartbeatLoopEnd") &&
                    helloReceived &&
                    !authFailure
                ) {
                    logSystem("MVP WS: heartbeat loop ended unexpectedly gen=$sessionGeneration")
                    transitionFsm(TelemetryConnectionPhase.Backoff, "heartbeat loop ended")
                    forceClose(client, "heartbeat loop ended")
                    _connectionState.value = ConnectionState.Disconnected()
                }
            }
    }

    private fun startHeartbeatWatchdog(
        client: MvpWsClient,
        sessionGeneration: Long,
    ) {
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob =
            appScope.launch {
                while (
                    isActive &&
                    acceptSession(client, sessionGeneration, "heartbeatWatchdog") &&
                    helloReceived &&
                    !authFailure
                ) {
                    delay(HEARTBEAT_WATCHDOG_CHECK_INTERVAL_MS)
                    if (!acceptSession(client, sessionGeneration, "heartbeatWatchdog")) break
                    if (_connectionState.value !is ConnectionState.Connected) continue
                    val timeoutMs = (2 * heartbeatIntervalSeconds + HEARTBEAT_ACK_GRACE_SEC) * 1000L
                    val elapsed = System.currentTimeMillis() - lastHeartbeatAckAtMs
                    if (elapsed > timeoutMs) {
                        val degradedHint =
                            if (!networkValidated) " networkDegraded=true" else ""
                        logSystem(
                            "MVP WS: heartbeat ack timeout elapsedMs=$elapsed timeoutMs=$timeoutMs " +
                                "intervalSec=$heartbeatIntervalSeconds lastHeartbeatId=$lastHeartbeatMessageId " +
                                "gen=$sessionGeneration$degradedHint",
                        )
                        transitionFsm(TelemetryConnectionPhase.Backoff, "heartbeat ack timeout")
                        forceClose(client, "heartbeat ack timeout")
                        _connectionState.value = ConnectionState.Disconnected()
                        break
                    }
                }
            }
    }

    private suspend fun sendHeartbeat(
        client: MvpWsClient,
        sessionGeneration: Long,
        temperatureC: Double?,
    ) {
        if (!acceptSession(client, sessionGeneration, "sendHeartbeat")) return
        val payload =
            buildJsonObject {
                put("state", JsonPrimitive("idle"))
                put("appVersionName", JsonPrimitive(BuildConfig.VERSION_NAME))
                put("appVersionCode", JsonPrimitive(BuildConfig.VERSION_CODE))
                temperatureC?.let { put("temperatureC", JsonPrimitive(it)) }
            }
        val messageId = java.util.UUID.randomUUID().toString()
        lastHeartbeatMessageId = messageId
        sendEnvelope("heartbeat", payload, messageId)
            .onFailure {
                Timber.w(it, "MvpTelemetry heartbeat failed")
                if (acceptSession(client, sessionGeneration, "heartbeatSendFailed")) {
                    forceClose(client, "heartbeat send failed")
                }
            }
    }

    private fun forceClose(
        client: MvpWsClient,
        reason: String,
    ) {
        if (client !== activeClient) return
        logSystem("MVP WS: force close — $reason gen=${client.sessionGeneration}")
        runCatching { client.close(1000, reason) }
            .onFailure { runCatching { client.close() } }
    }

    private fun cancelLivenessJobs() {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob = null
    }

    private fun resetHeartbeatAckTracking() {
        lastHeartbeatMessageId = null
        lastHeartbeatAckAtMs = 0L
    }

    private fun transitionFsm(
        to: TelemetryConnectionPhase,
        reason: String,
    ) {
        fsm.transition(to, reason)?.let { logFsmStructured(it) }
    }

    private fun logFsmStructured(transition: FsmTransition) {
        logSystem(fsm.formatTransitionLog(transition))
    }

    private fun shouldLogHeartbeatTraffic(): Boolean {
        val n = heartbeatTrafficLogCounter.incrementAndGet()
        return n == 1 || n % HEARTBEAT_TRAFFIC_LOG_EVERY_N == 0
    }

    private fun shouldLogTransportPing(): Boolean {
        val n = transportPingLogCounter.incrementAndGet()
        return n == 1 || n % TRANSPORT_PING_LOG_EVERY_N == 0
    }

    private fun logTransportPing() {
        if (!shouldLogTransportPing()) return
        logSystem("MVP WS transport: PING (server → client)")
    }

    private fun logTransportPong() {
        if (!shouldLogTransportPing()) return
        logSystem("MVP WS transport: PONG (client → server)")
    }

    private fun logSystem(summary: String) {
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.WS,
            direction = NetworkTrafficDirection.SYSTEM,
            summary = summary,
            payload = summary,
        )
        Timber.tag(SHIPPED_WS_LOG_TAG).i(summary)
    }

    private fun logIn(
        payload: String,
        wsType: String?,
    ) {
        if (wsType == "ack") return
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.WS,
            direction = NetworkTrafficDirection.IN,
            summary = extractTypeSummary(payload, wsType),
            payload = redactNetworkPayload(payload, wsType),
        )
    }

    private fun logOut(
        payload: String,
        wsType: String?,
    ) {
        if (wsType == "heartbeat" && !shouldLogHeartbeatTraffic()) return
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.WS,
            direction = NetworkTrafficDirection.OUT,
            summary = extractTypeSummary(payload, wsType),
            payload = redactNetworkPayload(payload, wsType),
        )
    }

    private fun extractWsType(payload: String): String? =
        runCatching {
            json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()

    private fun extractTypeSummary(
        payload: String,
        wsType: String? = null,
    ): String {
        val type = wsType ?: extractWsType(payload)
        return if (type.isNullOrBlank()) "MVP WS message" else "MVP WS type=$type"
    }

    internal inner class MvpWsClient(
        val sessionGeneration: Long,
        uri: URI,
        bearer: String,
        private val onOpenCallback: (ServerHandshake) -> Unit,
        private val onText: (String) -> Unit,
        private val onClosed: (Int, String) -> Unit,
        private val onErrorCallback: () -> Unit,
        private val onTransportPing: () -> Unit,
        private val onTransportPong: () -> Unit,
    ) : WebSocketClient(uri) {
        init {
            addHeader("Authorization", bearer)
            connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SEC
        }

        override fun onOpen(handshake: ServerHandshake) = onOpenCallback(handshake)

        override fun onMessage(message: String) = onText(message)

        override fun onMessage(bytes: ByteBuffer) = Unit

        override fun onClose(
            code: Int,
            reason: String,
            remote: Boolean,
        ) = onClosed(code, reason)

        override fun onError(ex: Exception) {
            Timber.w(ex, "MvpTelemetry WS")
            onErrorCallback()
        }

        override fun onWebsocketPing(
            conn: org.java_websocket.WebSocket?,
            f: org.java_websocket.framing.Framedata?,
        ) {
            onTransportPing()
            super.onWebsocketPing(conn, f)
            onTransportPong()
        }
    }
}
