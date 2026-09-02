package com.viwa.android.hardware.controller

import android.os.SystemClock
import com.viwa.android.logging.diagnostics.ControllerCommandDiagnosticsListener
import com.viwa.android.logging.diagnostics.NoOpControllerCommandDiagnostics
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * + базовый serial (мок-путь без физического порта).
 */
class ControllerConnection(
    private val devicePath: String,
    private val baudRate: Int,
    private val protocol: ControllerProtocol,
    private val transport: ControllerSerialTransport,
 /** Scope для таймеров мока и RX; отменяется при [close]. */
    private val connectionScope: CoroutineScope,
    private val onCommandLog: (CommandLogEntry) -> Unit,
    private val onNotConnected: () -> Unit,
    private val onPortNotFound: () -> Unit,
    private val emitResponse: (ResponseCommand, ByteArray) -> Unit,
 /** Сырые TX/RX байты до/после протокола — для отладки без анализатора. */
    private val onRawLog: ((direction: String, bytes: ByteArray, note: String) -> Unit)? = null,
    private val commandDiagnostics: ControllerCommandDiagnosticsListener = NoOpControllerCommandDiagnostics,
) {
    val isMockPort: Boolean
        get() = devicePath.startsWith(ControllerConstants.MOCK_PORT_PREFIX)

 /** После [init]: физический/мок-порт успешно открыт. */
    val isLinkOpen: Boolean
        get() = transport.isOpen

    private val rxMutex = Mutex()
    private var rxBuffer = ByteArray(0)
    private var isConnecting = false
    private var aliveJob: Job? = null
    private val mockJobs = mutableListOf<Job>()
    private var firstMessageReceived = false
    private var connectionCheckStep = 0

    suspend fun init() {
        if (isConnecting || transport.isOpen) {
            Timber.tag(TAG).i("init() skipped — already connecting or open path=%s", devicePath)
            return
        }
        isConnecting = true
        try {
            Timber.tag(TAG).i("init() path=%s mock=%s", devicePath, isMockPort)
            transport.setOnBytesReceived { chunk ->
                connectionScope.launch {
                    appendIncoming(chunk)
                }
            }
            val settings = ControllerPortSettings(devicePath = devicePath, baudRate = baudRate)
            val ok = transport.open(settings)
            if (!ok && !isMockPort) {
                onPortNotFound()
            }
            if (ok) {
                onConnect()
            }
        } finally {
            isConnecting = false
        }
    }

    fun close() {
        aliveJob?.cancel()
        aliveJob = null
        mockJobs.forEach { it.cancel() }
        mockJobs.clear()
        transport.close()
        connectionCheckStep = 0
        firstMessageReceived = false
        rxBuffer = ByteArray(0)
        Timber.tag(TAG).i("connection closed path=%s", devicePath)
    }

    suspend fun reconnect() {
        close()
        init()
    }

    suspend fun sendCommand(command: RequestCommand, body: ByteArray) {
        val ints = body.map { it.toInt() and 0xff }
        val opId = commandDiagnostics.nextOpId()
        commandDiagnostics.onOpBegin(opId, command.code)
        if (isMockPort) {
            val entry = CommandLogEntry.tx(command, body)
            onCommandLog(entry)
            val writeStarted = SystemClock.elapsedRealtime()
            simulateMockResponse(command, ints)
            val writeMs = SystemClock.elapsedRealtime() - writeStarted
            commandDiagnostics.onOpWritten(opId, command.code, writeMs, mock = true)
            return
        }
        if (!transport.isOpen) {
            Timber.tag(TAG).w(
                "port not open, skip send cmd=0x%02x path=%s",
                command.code,
                devicePath,
            )
            onCommandLog(CommandLogEntry.tx(command, body))
            commandDiagnostics.onOpFailed(opId, command.code, "port_not_open")
            onNotConnected()
            return
        }
        val message = protocol.formatRequest(command, body)
        Timber.tag(TAG).i(
            "TX cmd=0x%02x opId=%d body=%s frame=%s",
            command.code,
            opId,
            ints.joinToString(",") { it.toString() },
            message.joinToString(" ") { "%02X".format(it) },
        )
        onRawLog?.invoke("TX", message, "cmd=${command.name}")
        val writeStarted = SystemClock.elapsedRealtime()
        try {
            transport.write(message)
        } catch (error: Exception) {
            commandDiagnostics.onOpFailed(opId, command.code, error.javaClass.simpleName)
            throw error
        }
        val writeMs = SystemClock.elapsedRealtime() - writeStarted
        commandDiagnostics.onOpWritten(opId, command.code, writeMs, mock = false)
        onCommandLog(CommandLogEntry.tx(command, body))
    }

    private suspend fun appendIncoming(chunk: ByteArray) {
        rxMutex.withLock {
            val hexChunk = chunk.joinToString(" ") { "%02X".format(it) }
            Timber.tag(TAG).i("RX raw %d bytes: %s", chunk.size, hexChunk)
            onRawLog?.invoke("RX", chunk, "")

            if (!firstMessageReceived) {
                firstMessageReceived = true
                aliveJob?.cancel()
                aliveJob = null
            }
            rxBuffer = rxBuffer + chunk
            val (messages, rest) = protocol.processBuffer(rxBuffer)
            if (messages.isEmpty() && chunk.isNotEmpty()) {
                Timber.tag(TAG).w("RX: %d bytes не распознаны протоколом (мусор или неполный кадр)", chunk.size)
            }
            rxBuffer = rest
            for (msg in messages) {
                Timber.tag(TAG).i(
                    "RX parsed cmd=0x%02x len=%d",
                    msg.command.code,
                    msg.payload.size,
                )
                emitResponse(msg.command, msg.payload)
                advanceConnectionCheck(msg.command)
                onCommandLog(CommandLogEntry.rx(msg.command, msg.payload))
            }
        }
    }

    private suspend fun advanceConnectionCheck(responseCommand: ResponseCommand) {
        if (connectionCheckStep == 1 && responseCommand == ResponseCommand.ControllerVersionAnswer) {
            connectionCheckStep = 2
            sendCommand(RequestCommand.ReadControllerErrors, byteArrayOf(0, 0, 0, 0, 0))
            return
        }
        if (connectionCheckStep == 2 && responseCommand == ResponseCommand.ControllerMsgError) {
            connectionCheckStep = 3
            sendCommand(RequestCommand.ReadDeviceMode, byteArrayOf(0, 0, 0, 0, 0))
            return
        }
        if (connectionCheckStep == 3 && responseCommand == ResponseCommand.AutoChangeDeviceMode) {
            connectionCheckStep = 0
        }
    }

    private fun onConnect() {
        Timber.tag(TAG).i("onConnect path=%s", devicePath)
        if (isMockPort) return
        firstMessageReceived = false
        connectionCheckStep = 1
        if (ControllerConstants.ALIVE_CHECK_TIMEOUT_MS > 0L) {
            aliveJob =
                connectionScope.launch {
                    delay(ControllerConstants.ALIVE_CHECK_TIMEOUT_MS)
                    if (!firstMessageReceived) {
                        Timber.tag(TAG).w("controller alive timeout path=%s", devicePath)
                        close()
                        onPortNotFound()
                    }
                }
        }
        connectionScope.launch {
            sendCommand(RequestCommand.ReadFirmwareVersion, ControllerConstants.DEFAULT_BODY)
        }
    }

    private fun simulateMockResponse(command: RequestCommand, body: List<Int>) {
        val empty = byteArrayOf(0, 0, 0, 0, 0)
        val ack = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())

        fun logRx(responseCommand: ResponseCommand, payload: ByteArray) {
            onCommandLog(CommandLogEntry.rx(responseCommand, payload))
            emitResponse(responseCommand, payload)
        }

        fun schedule(delayMs: Long, block: suspend () -> Unit) {
            val job =
                connectionScope.launch {
                    try {
                        delay(delayMs)
                        block()
                    } catch (_: CancellationException) {
                    }
                }
            synchronized(mockJobs) { mockJobs.add(job) }
        }

        if (command == RequestCommand.ServiceCommand && (body.getOrNull(0) ?: 0) == 0x09) {
            Timber.tag(TAG).i(
                "mock: тестовый налив сиропа (ServiceCommand 0x09) body=%s",
                body.joinToString(","),
            )
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerACK, ack)
            }
            return
        }
        if (command == RequestCommand.ServiceCommand &&
            ((body.getOrNull(0) ?: 0) == 0x0a || (body.getOrNull(0) ?: 0) == 0x0b)
        ) {
            val volumeMl = (body.getOrElse(4) { 0 }) * 10
            val durationSec =
                if (volumeMl > 0) {
                    volumeMl / ControllerConstants.MOCK_WATER_POUR_SPEED_ML_PER_SEC
                } else {
                    10.0
                }
            schedule(ControllerConstants.MOCK_BEGIN_DELAY_MS) {
                logRx(ResponseCommand.DrinkPreparingBegin, empty)
                logRx(ResponseCommand.ControllerACK, ack)
            }
            schedule(ControllerConstants.MOCK_BEGIN_DELAY_MS + (durationSec * 1000).roundToInt().toLong()) {
                logRx(ResponseCommand.DrinkPreparingSuccess, empty)
            }
            return
        }
        if ((command == RequestCommand.ServiceCommand && (body.getOrNull(0) ?: 0) == 0x07) ||
            command == RequestCommand.ReadDeviceMode
        ) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.AutoChangeDeviceMode, byteArrayOf(1, 0, 0, 0, 0))
            }
            return
        }
        if (command == RequestCommand.ReadWaterPumpModel) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(
                    ResponseCommand.WaterPumpModelAnswer,
                    byteArrayOf(50, 50, 0, 80, 80),
                )
            }
            return
        }
        if (command == RequestCommand.WriteWaterPumpModel) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerACK, ack)
            }
            return
        }
        if (command == RequestCommand.ReadWaterCounter) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(
                    ResponseCommand.WaterCounterAnswer,
                    byteArrayOf(0x05, 0xdc.toByte(), 0x00, 0x05, 0xdc.toByte()),
                )
            }
            return
        }
        if (command == RequestCommand.ResetWaterCounter) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerACK, ack)
            }
            return
        }
        if (command == RequestCommand.ReadFlowTemperature) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerTimeoutResetActivate, byteArrayOf(22, 45, 0, 0, 0))
            }
            return
        }
        if (command == RequestCommand.SetFlowRgb) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerACK, ack)
            }
            return
        }
        if (command == RequestCommand.ReadFlowBucketStatus) {
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.CupSensorStatusAnswer, byteArrayOf(0, 0, 0, 0, 0))
            }
            return
        }
        if (command == RequestCommand.WaterPourByTouch) {
            Timber.tag(TAG).i(
                "mock: WaterPourByTouch (налив по кнопке) body=%s → ControllerACK",
                body.joinToString(","),
            )
            schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
                logRx(ResponseCommand.ControllerACK, ack)
            }
            return
        }
        schedule(ControllerConstants.MOCK_ACK_DELAY_MS) {
            logRx(ResponseCommand.ControllerACK, ack)
        }
    }

    companion object {
        private const val TAG = "ViwaController"
    }
}
