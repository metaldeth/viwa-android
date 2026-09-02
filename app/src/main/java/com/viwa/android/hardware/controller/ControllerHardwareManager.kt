package com.viwa.android.hardware.controller

import android.content.Context
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.di.AppIoScope
import com.viwa.android.logging.diagnostics.ControllerCommandDiagnosticsListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

@Singleton
class ControllerHardwareManager
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val protocol: ControllerProtocol,
    private val configRepository: ConfigRepository,
    private val trafficLogger: ViwaControllerTrafficLogger,
    private val rawLogger: ViwaControllerRawLogger,
    private val commandDiagnostics: ControllerCommandDiagnosticsListener,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val managerMutex = Mutex()

    private var connectionSupervisor: Job = SupervisorJob()
    private var connectionScope =
        CoroutineScope(connectionSupervisor + Dispatchers.Default)

    private var activeConnection: ControllerConnection? = null

    private val _incoming =
        MutableSharedFlow<ControllerResponseEvent>(
            replay = 0,
            extraBufferCapacity = 128,
        )
    val incomingResponses: SharedFlow<ControllerResponseEvent> = _incoming.asSharedFlow()

    private val _notConnected =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 4,
        )
    val controllerNotConnectedEvents: SharedFlow<Unit> = _notConnected.asSharedFlow()

    private val _isPhysicalControllerConnected = MutableStateFlow(false)

 /** `true`, когда активно реальное USB-соединение (не мок). */
    val isPhysicalControllerConnected: StateFlow<Boolean> = _isPhysicalControllerConnected.asStateFlow()

    private var connectWaitJob: Job? = null

    private val afterInitializeFromConfigCallbacks =
        CopyOnWriteArrayList<suspend () -> Unit>()

 /**
 * Вызывается после успешного [initializeFromConfig] (вне [managerMutex], чтобы колбэки могли слать команды).
 * Если соединение уже есть (поздняя регистрация), колбэк выполняется сразу в [appScope].
 */
    fun registerAfterInitializeFromConfig(callback: suspend () -> Unit) {
        afterInitializeFromConfigCallbacks.add(callback)
        if (hasActiveConnection()) {
            appScope.launch {
                runCatching { callback() }
            }
        }
    }

    private suspend fun invokeAfterInitializeFromConfigCallbacks() {
        for (cb in afterInitializeFromConfigCallbacks) {
            runCatching { cb() }
        }
    }

    init {
        appScope.launch {
            delay(ControllerConstants.CONTROLLER_STARTUP_DELAY_MS)
            runCatching { initializeFromConfig() }
                .onFailure { Timber.tag(TAG).e(it, "controller init failed") }
        }
    }

    private suspend fun useMockControllerSetting(): Boolean =
        configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER) == "true"

    private suspend fun resolveDevicePath(): String {
        val mock = useMockControllerSetting()
        val saved = configRepository.get(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH)?.trim().orEmpty()
        val useReal = !mock && saved.isNotEmpty()
        return if (useReal) saved else ControllerConstants.MOCK_CONTROLLER_PATH
    }

    private fun rebuildConnectionScope() {
        connectionSupervisor.cancel()
        connectWaitJob?.cancel()
        connectWaitJob = null
        connectionSupervisor = SupervisorJob()
        connectionScope = CoroutineScope(connectionSupervisor + Dispatchers.Default)
    }

    private fun createTransportForPath(path: String): ControllerSerialTransport =
        when {
            path.startsWith(ControllerConstants.MOCK_PORT_PREFIX) -> MockControllerSerialTransport()
            path.startsWith("/dev/ttyS") || path.startsWith("/dev/ttyACM") || path.startsWith("/dev/ttyUSB") ->
                TtySerialControllerTransport(appScope)
            else -> UsbSerialControllerTransport(appContext, appScope)
        }

    private fun attachNewConnection(path: String): ControllerConnection {
        rebuildConnectionScope()
        val transport = createTransportForPath(path)
        rawLogger.logEvent(path, "open → $path")
        val conn =
            ControllerConnection(
                devicePath = path,
                baudRate = 9600,
                protocol = protocol,
                transport = transport,
                connectionScope = connectionScope,
                onCommandLog = { entry -> trafficLogger.log(entry) },
                onNotConnected = {
                    _isPhysicalControllerConnected.value = false
                    appScope.launch {
                        _notConnected.emit(Unit)
                    }
                },
                onPortNotFound = {
                    Timber.tag(TAG).w("onPortNotFound path=%s", path)
                    rawLogger.logEvent(path, "port not found")
                },
                emitResponse = { cmd, payload ->
                    appScope.launch {
                        _incoming.emit(ControllerResponseEvent(cmd, payload))
                    }
                },
                onRawLog = { dir, bytes, note ->
                    if (dir == "TX") rawLogger.logTx(path, bytes)
                    else rawLogger.logRx(path, bytes, note)
                },
                commandDiagnostics = commandDiagnostics,
            )
        activeConnection = conn
        return conn
    }

 /** Пересоздать соединение из JsonStore (мок/путь). */
    suspend fun initializeFromConfig() {
        managerMutex.withLock {
            val path = resolveDevicePath()
            activeConnection?.close()
            activeConnection = null
            _isPhysicalControllerConnected.value = false
            val conn = attachNewConnection(path)
            conn.init()
            _isPhysicalControllerConnected.value = !conn.isMockPort
        }
        invokeAfterInitializeFromConfigCallbacks()
    }

 /**
 * Запись TX во вкладку «Дебаг контроллера» без вызова [ControllerConnection.sendCommand].
 * Нужна там, где эталон намеренно не шлёт кадр на шину (например мок
 * [DrinkPreparingService]: только таймеры и события), но в логе TX должен быть виден.
 */
    fun logTxOnlyForDebugUi(command: RequestCommand, payload: ByteArray) {
        trafficLogger.log(CommandLogEntry.tx(command, payload))
    }

    suspend fun sendCommand(command: RequestCommand, payload: ByteArray) =
        managerMutex.withLock {
            val conn = activeConnection
            if (conn == null) {
                Timber.tag(TAG).w("sendCommand: no connection")
                return@withLock
            }
            conn.sendCommand(command, payload)
        }

    suspend fun reconnect() =
        managerMutex.withLock {
            val conn = activeConnection ?: return@withLock
            conn.reconnect()
        }

 /**
 * Подключение к USB/UART-порту: открытие serial и сохранение пути в JsonStore.
 * Без ожидания ответов контроллера (прошивка / ошибки / режим) — только успех [ControllerConnection.init].
 */
    suspend fun connectToPort(devicePath: String): ConnectToPortResult =
        managerMutex.withLock {
            val trimmed = devicePath.trim()
            if (trimmed.isEmpty()) {
                return ConnectToPortResult(
                    success = false,
                    failedStep = ConnectToPortResult.ConnectFailedStep.FIRMWARE,
                )
            }
            connectWaitJob?.cancel()
            connectWaitJob = null
            activeConnection?.close()
            activeConnection = null
            val conn = attachNewConnection(trimmed)

            return try {
                conn.init()
                if (!conn.isLinkOpen) {
                    conn.close()
                    activeConnection = null
                    ConnectToPortResult(
                        success = false,
                        failedStep = ConnectToPortResult.ConnectFailedStep.FIRMWARE,
                    )
                } else {
                    configRepository.set(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH, trimmed)
                    _isPhysicalControllerConnected.value = true
                    ConnectToPortResult(success = true)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "connectToPort init failed path=%s", trimmed)
                conn.close()
                activeConnection = null
                ConnectToPortResult(
                    success = false,
                    failedStep = ConnectToPortResult.ConnectFailedStep.FIRMWARE,
                )
            }
        }

    fun isMockPortActive(): Boolean =
        activeConnection?.isMockPort == true

 /** Есть активное соединение (мок или UART) — иначе [sendCommand] не уходит на шину. */
    fun hasActiveConnection(): Boolean = activeConnection != null

    suspend fun readControllerErrorsCode(): Int =
        coroutineScope {
            val await =
                async {
                    _incoming.first { it.response == ResponseCommand.ControllerMsgError }
                }
            yield()
            sendCommand(RequestCommand.ReadControllerErrors, byteArrayOf(0, 0, 0, 0, 0))
            withTimeoutOrNull(3000L) {
                val ev = await.await()
                ev.payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
            } ?: 0
        }

    suspend fun readDeviceModeByte(): Int =
        coroutineScope {
            val await =
                async {
                    _incoming.first { it.response == ResponseCommand.AutoChangeDeviceMode }
                }
            yield()
            sendCommand(RequestCommand.ReadDeviceMode, byteArrayOf(0, 0, 0, 0, 0))
            withTimeoutOrNull(5000L) {
                val ev = await.await()
                ev.payload.firstOrNull()?.toInt()?.and(0xff) ?: -1
            } ?: -1
        }

    suspend fun setServiceModeCommand() {
        sendCommand(RequestCommand.ServiceCommand, byteArrayOf(0x06, 0, 0, 0, 0))
    }

    suspend fun setAutoModeCommand() {
        sendCommand(RequestCommand.ServiceCommand, byteArrayOf(0x07, 0, 0, 0, 0))
    }

 /** Для тестов UI / PAX: эмит RX только в мок-режиме. */
    suspend fun simulateResponseForTests(command: ResponseCommand, payload: ByteArray) {
        if (!useMockControllerSetting()) return
        trafficLogger.log(CommandLogEntry.rx(command, payload))
        _incoming.emit(ControllerResponseEvent(command, payload))
    }

    companion object {
        private const val TAG = "ViwaController"
    }
}
