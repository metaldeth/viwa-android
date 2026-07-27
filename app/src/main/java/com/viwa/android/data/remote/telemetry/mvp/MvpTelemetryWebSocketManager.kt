package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.BuildConfig
import com.viwa.android.data.network.NetworkTrafficChannel
import com.viwa.android.data.network.NetworkTrafficDirection
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.network.redactNetworkPayload
import com.viwa.android.data.remote.telemetry.ConnectionState
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MvpTelemetryWebSocketManager
@Inject
constructor(
    @AppIoScope private val appScope: CoroutineScope,
    private val networkTrafficLogger: NetworkTrafficLogger,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var connectJob: Job? = null
    private var activeClient: MvpWsClient? = null
    private var heartbeatJob: Job? = null
    private var helloReceived = false
    private var heartbeatIntervalSeconds = DEFAULT_HEARTBEAT_INTERVAL_SEC
    private var authFailure = false
    private var temperatureProvider: () -> Double? = { null }
    private val heartbeatTrafficLogCounter = AtomicInteger(0)
    private val transportPingLogCounter = AtomicInteger(0)

    /** Делегат cells sync; wiring из [SimpleTelemetryCoordinator]. */
    var cellsSyncHandler: MvpTelemetryCellsSyncHandler? = null

    /** Делегат loyalty WS; wiring из [ViwaTelemetryService]. */
    var loyaltySyncHandler: MvpTelemetryLoyaltySyncHandler? = null

    private companion object {
        val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 10
        const val AUTH_CLOSE_CODE = 4401
        const val HEARTBEAT_TRAFFIC_LOG_EVERY_N = 20
        const val TRANSPORT_PING_LOG_EVERY_N = 10
    }

    fun reportAuthFailure(message: String) {
        authFailure = true
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
                    var attempt = 0
                    while (isActive && !authFailure) {
                        helloReceived = false
                        heartbeatJob?.cancel()
                        _connectionState.value = ConnectionState.Connecting
                        logSystem("MVP WS: подключение $wsUrl")

                        val delayMs = reconnectDelayMs(attempt)
                        if (attempt > 0) {
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
                            if (_connectionState.value !is ConnectionState.Error) {
                                _connectionState.value = ConnectionState.Error("Не удалось получить JWT")
                            }
                            break
                        }

                        try {
                            suspendCancellableCoroutine { cont ->
                                cont.invokeOnCancellation { activeClient?.close() }

                                val client =
                                    MvpWsClient(
                                        uri = URI.create(wsUrl),
                                        bearer = "Bearer $bearerToken",
                                        onOpenCallback = { handshake ->
                                            if (handshake.httpStatus == 401.toShort() || handshake.httpStatus == 403.toShort()) {
                                                authFailure = true
                                                _connectionState.value =
                                                    ConnectionState.Error("Ошибка авторизации WS (HTTP ${handshake.httpStatus})")
                                                logSystem("MVP WS: auth failure HTTP ${handshake.httpStatus}")
                                                onAuthFailure()
                                                if (cont.isActive) cont.resume(Unit) {}
                                                return@MvpWsClient
                                            }
                                            logSystem("MVP WS: сокет открыт HTTP ${handshake.httpStatus}")
                                        },
                                        onText = { text -> handleIncoming(text) },
                                        onClosed = { code, reason ->
                                            if (code == AUTH_CLOSE_CODE || code == 1008 || code == 1002) {
                                                authFailure = true
                                                _connectionState.value =
                                                    ConnectionState.Error("Ошибка авторизации WS: $reason")
                                                onAuthFailure()
                                            }
                                            if (cont.isActive) cont.resume(Unit) {}
                                        },
                                        onErrorCallback = {
                                            if (cont.isActive) cont.resume(Unit) {}
                                        },
                                        onTransportPing = { logTransportPing() },
                                        onTransportPong = { logTransportPong() },
                                    )
                                activeClient = client
                                client.connect()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "MvpTelemetry WS connect exception")
                        }

                        activeClient?.close()
                        activeClient = null
                        heartbeatJob?.cancel()

                        if (authFailure) break
                        if (helloReceived) {
                            attempt = 0
                        } else {
                            attempt++
                        }

                        if (!helloReceived && !authFailure) {
                            _connectionState.value = ConnectionState.Disconnected(reconnectDelayMs(attempt))
                        }
                    }
                }
            }
    }

    fun disconnect() {
        logSystem("MVP WS: отключение по запросу")
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        activeClient?.close()
        activeClient = null
        helloReceived = false
        authFailure = false
        heartbeatTrafficLogCounter.set(0)
        transportPingLogCounter.set(0)
        _connectionState.value = ConnectionState.Disconnected()
    }

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

    private fun handleIncoming(text: String) {
        val wsType = extractWsType(text)
        logIn(text, wsType = wsType)
        runCatching {
            val envelope = json.decodeFromString(MvpWsEnvelopeDto.serializer(), text)
            when (envelope.type) {
                "hello" -> onHello(envelope)
                "ack" -> onAck(envelope)
                "error" -> onError(envelope)
                "cells.snapshot" -> onCellsSnapshot(envelope)
                LoyaltyWsCodec.TYPE_STATUS_CHANGED -> onLoyaltyStatusChanged(envelope)
                else -> Timber.d("MvpTelemetry WS: ignored type=${envelope.type}")
            }
        }.onFailure { Timber.w(it, "MvpTelemetry WS parse failed") }
    }

    private fun onHello(envelope: MvpWsEnvelopeDto) {
        val payloadEl = envelope.payload ?: return
        val hello = json.decodeFromJsonElement(MvpHelloPayloadDto.serializer(), payloadEl)
        heartbeatIntervalSeconds = hello.heartbeatIntervalSeconds.coerceAtLeast(5)
        helloReceived = true
        _connectionState.value = ConnectionState.Connected
        logSystem("MVP WS: ONLINE serial=${hello.serialNumber}, heartbeat=${heartbeatIntervalSeconds}s")
        startHeartbeatLoop()
        appScope.launch {
            runCatching { cellsSyncHandler?.onWebSocketHello() }
                .onFailure { Timber.w(it, "MvpTelemetry WS: cells sync onHello failed") }
        }
    }

    private fun onAck(envelope: MvpWsEnvelopeDto) {
        val correlation =
            envelope.correlationId
                ?: envelope.payload?.jsonObject?.get("correlationId")?.jsonPrimitive?.content
        Timber.d("MvpTelemetry WS: ack correlationId=$correlation")
        val payload = envelope.payload?.jsonObject ?: return
        if (payload.containsKey("schemaHash")) {
            appScope.launch {
                runCatching { cellsSyncHandler?.onSchemaAck(payload) }
                    .onFailure { Timber.w(it, "MvpTelemetry WS: cells sync schema ack failed") }
            }
            return
        }
        if (correlation.isNullOrBlank()) return
        appScope.launch {
            runCatching { loyaltySyncHandler?.onLoyaltyAck(correlation, payload) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: loyalty ack handler failed") }
        }
    }

    private fun onError(envelope: MvpWsEnvelopeDto) {
        val correlation = envelope.correlationId
        val payload = envelope.payload?.jsonObject ?: return
        val code = payload["code"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val message = payload["message"]?.jsonPrimitive?.content ?: code
        appScope.launch {
            runCatching { loyaltySyncHandler?.onLoyaltyError(correlation, code, message) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: loyalty error handler failed") }
        }
    }

    private fun onLoyaltyStatusChanged(envelope: MvpWsEnvelopeDto) {
        val payload = envelope.payload?.jsonObject ?: return
        appScope.launch {
            runCatching { loyaltySyncHandler?.onStatusChanged(payload) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: loyalty.status.changed failed") }
        }
    }

    private fun onCellsSnapshot(envelope: MvpWsEnvelopeDto) {
        val payloadEl = envelope.payload ?: return
        val payloadJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payloadEl)
        appScope.launch {
            runCatching { cellsSyncHandler?.onCellsSnapshot(payloadJson) }
                .onFailure { Timber.w(it, "MvpTelemetry WS: cells.snapshot handler failed") }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob =
            appScope.launch {
                while (isActive && helloReceived && activeClient?.readyState == ReadyState.OPEN) {
                    delay(heartbeatIntervalSeconds * 1000L)
                    sendHeartbeat(temperatureProvider())
                }
            }
    }

    private suspend fun sendHeartbeat(temperatureC: Double?) {
        val payload =
            buildJsonObject {
                put("state", JsonPrimitive("idle"))
                put("appVersionName", JsonPrimitive(BuildConfig.VERSION_NAME))
                put("appVersionCode", JsonPrimitive(BuildConfig.VERSION_CODE))
                temperatureC?.let { put("temperatureC", JsonPrimitive(it)) }
            }
        sendEnvelope("heartbeat", payload)
            .onFailure { Timber.w(it, "MvpTelemetry heartbeat failed") }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val index = attempt.coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)
        val base = RECONNECT_DELAYS_MS[index]
        val jitter = (base * 0.1 * Random.nextDouble()).toLong()
        return base + jitter
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
    }

    private fun logIn(payload: String, wsType: String?) {
        if (wsType == "ack") return
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.WS,
            direction = NetworkTrafficDirection.IN,
            summary = extractTypeSummary(payload, wsType),
            payload = redactNetworkPayload(payload),
        )
    }

    private fun logOut(payload: String, wsType: String?) {
        if (wsType == "heartbeat" && !shouldLogHeartbeatTraffic()) return
        networkTrafficLogger.log(
            channel = NetworkTrafficChannel.WS,
            direction = NetworkTrafficDirection.OUT,
            summary = extractTypeSummary(payload, wsType),
            payload = redactNetworkPayload(payload),
        )
    }

    private fun extractWsType(payload: String): String? =
        runCatching {
            json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()

    private fun extractTypeSummary(payload: String, wsType: String? = null): String {
        val type = wsType ?: extractWsType(payload)
        return if (type.isNullOrBlank()) "MVP WS message" else "MVP WS type=$type"
    }

    private inner class MvpWsClient(
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
        }

        override fun onOpen(handshake: ServerHandshake) = onOpenCallback(handshake)

        override fun onMessage(message: String) = onText(message)

        override fun onMessage(bytes: ByteBuffer) = Unit

        override fun onClose(code: Int, reason: String, remote: Boolean) = onClosed(code, reason)

        override fun onError(ex: Exception) {
            Timber.w(ex, "MvpTelemetry WS")
            onErrorCallback()
        }

        override fun onWebsocketPing(conn: org.java_websocket.WebSocket?, f: org.java_websocket.framing.Framedata?) {
            onTransportPing()
            super.onWebsocketPing(conn, f)
            onTransportPong()
        }
    }
}
