package com.viwa.android.data.payment.aqsi

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.viwa.android.data.payment.aqsi.UsbPaymentResult
import com.viwa.android.data.payment.aqsi.UsbPaymentStatus
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.data.payment.aqsi.network.AqsiPillHostNetworkBootstrap
import com.viwa.android.data.payment.aqsi.network.AqsiPillNetworkConstants
import com.viwa.android.data.payment.aqsi.network.AqsiPillNetworkRouter
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialConfig
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialLink
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.services.payment.ConcurrentPaymentGuard
import com.viwa.android.services.payment.PillUsbLeaseResult
import com.viwa.android.services.payment.PillUsbOwner
import com.viwa.android.services.payment.PillUsbSessionOwner
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val AQSI_BAUD_RATE = 115200
private const val AQSI_PROBE_TIMEOUT_MS = 5_000L
private const val AQSI_PAYMENT_TIMEOUT_MS = 180_000L
private const val AQSI_CARD_WAIT_TIMEOUT_THRESHOLD_MS = 45_000L
private const val AQSI_CLEANUP_TIMEOUT_MS = 4_000L
private const val AQSI_CLEANUP_READ_TIMEOUT_MS = 400L
private const val AQSI_PREPAY_CLEANUP_TIMEOUT_MS = 2_000L
private const val AQSI_PREPAY_CLEANUP_READ_MS = 300L
private const val AQSI_CANCEL_READ_TIMEOUT_MS = 500L
private const val ARCUS_STX: Byte = 0x01
private const val ARCUS_ESC = 0x1B.toChar()
private const val TAG_AQSI = "AQSI"
private const val AQSI_DEFAULT_HOST = AqsiPillNetworkConstants.PILL_HOST
private const val AQSI_JPAY_PORT = AqsiPillNetworkConstants.JPAY_PORT
private val whitespaceRegex = Regex("\\s+")

private data class ArcusCommand(val name: String, val data: ByteArray)

@Singleton
class AqsiUsbPaymentManager
@Inject
constructor(
    private val usbSerialAccess: AqsiUsbSerialAccess,
    private val serialPort: PaymentSerialPort,
    private val audit: AqsiPaymentAuditLogger,
    private val pillNetworkRouter: AqsiPillNetworkRouter,
    private val hostNetworkBootstrap: AqsiPillHostNetworkBootstrap,
    private val pillUsbSessionOwner: PillUsbSessionOwner,
    private val concurrentPaymentGuard: ConcurrentPaymentGuard,
) {
    private val _stateFlow = MutableStateFlow(UsbPaymentStatus.IDLE)
    private val _terminalStatusFlow = MutableStateFlow("")
    private val _exchangeLogFlow = MutableStateFlow(emptyList<String>())
    private val charset = Charset.forName("windows-1251")
    private val logTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var cancelRequested = false
    @Volatile
    private var activeSockets: MutableMap<Int, ArcusSocket>? = null

    val stateFlow: StateFlow<UsbPaymentStatus> = _stateFlow.asStateFlow()
    val terminalStatusFlow: StateFlow<String> = _terminalStatusFlow.asStateFlow()
    val exchangeLogFlow: StateFlow<List<String>> = _exchangeLogFlow.asStateFlow()

    suspend fun pay(amountKopecks: Int): UsbPaymentResult =
        concurrentPaymentGuard.runPayment { runArcus2Payment(amountKopecks) }

    suspend fun testPayment(): UsbPaymentResult =
        concurrentPaymentGuard.runPayment { runArcus2Payment(100) }

    suspend fun cancel() {
        cancelRequested = true
        _stateFlow.value = UsbPaymentStatus.CANCELLING
        _terminalStatusFlow.value = "Отменяем платёж на терминале"
        activeSockets?.values?.forEach { it.close() }
        appendExchangeLog("CANCEL requested socketsClosed=${activeSockets?.size ?: 0}")
    }

    private suspend fun runUsbProbe(reason: String): UsbPaymentResult =
        withContext(Dispatchers.IO) {
            resetExchangeLog("START reason=$reason")
            _stateFlow.value = UsbPaymentStatus.PROCESSING
            _terminalStatusFlow.value = "AQSI: поиск назначенного терминала"
            audit.log("INFO", "AQSI", "usb probe start reason=$reason")

            val driverResult = findAssignedAqsiDriver()
            if (driverResult is AssignedDriverResult.Failure) {
                _stateFlow.value = UsbPaymentStatus.FAILURE
                _terminalStatusFlow.value = "AQSI: ${driverResult.message}"
                audit.log("ERROR", "AQSI", driverResult.message)
                appendExchangeLog("FAIL ${driverResult.message}")
                return@withContext UsbPaymentResult.Failure(driverResult.errorCode, driverResult.message)
            }
            val driver = (driverResult as AssignedDriverResult.Success).driver
            appendExchangeLog("SCAN assignedDevice=${driver.device.deviceName}")

            val deviceLabel =
                driver.device.run {
                    "${deviceName} vid=%04x pid=%04x".format(vendorId, productId)
                }
            _terminalStatusFlow.value = "AQSI: найден $deviceLabel"
            audit.log("INFO", "AQSI", "candidate $deviceLabel driver=${driver::class.simpleName}")
            Log.i(TAG_AQSI, "candidate $deviceLabel ports=${driver.ports.size} driver=${driver::class.simpleName}")
            appendExchangeLog("DEVICE $deviceLabel driver=${driver::class.simpleName} ports=${driver.ports.size}")

            val lastFailures = mutableListOf<String>()
            for (portIndex in driver.ports.indices) {
                val result = probePort(driver, portIndex, deviceLabel)
                if (result is UsbPaymentResult.Success) return@withContext result
                lastFailures += "port$portIndex=${describeFailure(result)}"
            }

            val msg = "нет ответа AQSI (${lastFailures.joinToString()})"
            _stateFlow.value = UsbPaymentStatus.TIMEOUT
            _terminalStatusFlow.value = "AQSI: $msg"
            audit.log("WARN", "AQSI", msg)
            appendExchangeLog("RESULT TIMEOUT $msg")
            UsbPaymentResult.Timeout
        }

    /** @deprecated Provisioning-only (factory PC). Customer/test payment must use [runArcus2Payment]. */
    private suspend fun runJpayTcpPayment(amountKopecks: Int): UsbPaymentResult? =
        withContext(Dispatchers.IO) {
            resetExchangeLog("START JPAY TCP amountKopecks=$amountKopecks")
            cancelRequested = false
            _stateFlow.value = UsbPaymentStatus.CONNECTING
            _terminalStatusFlow.value = "AQSI JPAY: подключение к терминалу"
            when (val driverResult = findAssignedAqsiDriver()) {
                is AssignedDriverResult.Failure -> {
                    _stateFlow.value = UsbPaymentStatus.FAILURE
                    _terminalStatusFlow.value = driverResult.message
                    return@withContext UsbPaymentResult.Failure(driverResult.errorCode, driverResult.message)
                }
                is AssignedDriverResult.Success -> {
                    appendExchangeLog("JPAY device=${driverResult.driver.device.deviceName}")
                }
            }
            _stateFlow.value = UsbPaymentStatus.WAITING_FOR_CARD
            _terminalStatusFlow.value = "Приложите карту к терминалу"
            JpayTcpClient.tryPurchase(
                amountKopecks = amountKopecks,
                networkRouter = pillNetworkRouter,
                log = { line -> appendExchangeLog(line) },
                isCancelled = { cancelRequested },
                onTransactionSocket = { _ -> },
            )
                ?.also { result ->
                    when (result) {
                        is UsbPaymentResult.Success -> {
                            _stateFlow.value = UsbPaymentStatus.SUCCESS
                            _terminalStatusFlow.value = "Оплата прошла успешно"
                        }
                        is UsbPaymentResult.Failure -> {
                            _stateFlow.value = UsbPaymentStatus.FAILURE
                            _terminalStatusFlow.value = result.message
                        }
                        UsbPaymentResult.Cancelled -> {
                            _stateFlow.value = UsbPaymentStatus.CANCELLED
                            _terminalStatusFlow.value = "Оплата отменена"
                        }
                        UsbPaymentResult.Timeout -> {
                            _stateFlow.value = UsbPaymentStatus.TIMEOUT
                            _terminalStatusFlow.value = "Таймаут ожидания карты"
                        }
                    }
                }
        }

    private suspend fun runArcus2Payment(amountKopecks: Int): UsbPaymentResult =
        withContext(Dispatchers.IO) {
            resetExchangeLog("START Arcus2 payment amountKopecks=$amountKopecks")
            cancelRequested = false
            _stateFlow.value = UsbPaymentStatus.CONNECTING
            _terminalStatusFlow.value = "Подключаемся к платёжному терминалу"
            val networkStatus = hostNetworkBootstrap.runForPayment()
            appendExchangeLog(
                "NET paymentFastPath=${networkStatus.paymentFastPath} " +
                    "proxyCleared=${networkStatus.httpProxyCleared} ncm=${networkStatus.ncmReady} " +
                    "wifiBound=${networkStatus.wifiProcessBound} wifiProbe=${networkStatus.wifiInternetProbe} " +
                    "socks=${networkStatus.socksStarted}",
            )
            if (!networkStatus.paymentFastPath && !networkStatus.ncmReady) {
                Timber.tag(TAG_AQSI).w(
                    "Arcus2: NCM host link not ready (eth2 без %s) — CONNECT к SOCKS может не пройти",
                    AqsiPillNetworkConstants.PILL_GATEWAY_HOST,
                )
            } else if (networkStatus.paymentFastPath && !networkStatus.ncmReady) {
                Timber.tag(TAG_AQSI).i(
                    "Arcus2 payment fast-path: NCM not ready, direct CONNECT over Wi‑Fi expected",
                )
            }
            if (!networkStatus.wifiInternetProbe) {
                return@withContext fail(
                    "AQSI_NO_INTERNET",
                    "Нет интернета по Wi‑Fi — банковский туннель Arcus2 недоступен",
                )
            }
            val driverResult = findAssignedAqsiDriver()
            if (driverResult is AssignedDriverResult.Failure) {
                return@withContext fail(driverResult.errorCode, driverResult.message)
            }
            val driver = (driverResult as AssignedDriverResult.Success).driver
            val leaseResult =
                pillUsbSessionOwner.acquire(
                    PillUsbOwner.CUSTOMER_PAYMENT,
                    "Arcus2 payment amountKopecks=$amountKopecks",
                )
            val grantedLease =
                when (leaseResult) {
                    is PillUsbLeaseResult.Denied ->
                        return@withContext fail(leaseResult.code, leaseResult.message)
                    is PillUsbLeaseResult.Granted -> leaseResult.lease
                }
            try {
            val opened =
                usbSerialAccess.openConnection(
                    driver = driver,
                    config = AqsiSerialConfig(baudRate = AQSI_BAUD_RATE),
                ) ?: return@withContext fail("AQSI_OPEN_FAILED", "Не удалось открыть AQSI USB serial")
            val (connection, usbConnection) = opened
            val sockets = mutableMapOf<Int, ArcusSocket>()
            activeSockets = sockets
            var result: UsbPaymentResult? = null
            try {
                prepareArcus2Session(connection, sockets, amountKopecks)
                val start = buildArcusFrame(buildPaymentStartPayload(amountKopecks))
                usbWrite(connection, start, timeoutMs = 1_000, label = "TX_START")
                _stateFlow.value = UsbPaymentStatus.WAITING_FOR_CARD
                _terminalStatusFlow.value = "Приложите карту к терминалу"
                appendExchangeLog("TX_START ${start.toHex()} | opclass=1 opcode=1 amount=$amountKopecks currency=643")
                val operationStartedAt = System.currentTimeMillis()
                val deadline = System.currentTimeMillis() + AQSI_PAYMENT_TIMEOUT_MS
                var transactionId = "AQSI-${System.currentTimeMillis()}"
                var responseCode: AqsiResponseCode? = null
                while (System.currentTimeMillis() < deadline) {
                    if (cancelRequested) {
                        result = cancelled()
                        break
                    }
                    val readTimeout = if (cancelRequested) AQSI_CANCEL_READ_TIMEOUT_MS else 10_000L
                    val frame = readFrame(connection, readTimeout)
                    if (frame == null) {
                        if (cancelRequested) {
                            result = cancelled()
                            break
                        }
                        // Terminal may stay silent while waiting for card/bank (CONNECT/READ).
                        continue
                    }
                    val command = decodeArcusCommand(frame)
                    if (command == null) {
                        result = fail("AQSI_BAD_FRAME", "Не удалось разобрать команду AQSI")
                        break
                    }
                    appendExchangeLog("RX_CMD ${command.name}: ${command.data.toHex()}")
                    observeArcusCommand(command)?.let {
                        responseCode = it
                        transactionId = "AQSI-${it.code}-${System.currentTimeMillis()}"
                    }
                    val response = handleArcusCommand(command, sockets, amountKopecks)
                    appendExchangeLog("TX_CMD ${command.name}: ${response.toHex()}")
                    usbWrite(connection, response, timeoutMs = 2_000, label = "TX_CMD ${command.name}")
                    if (command.name == "ENDTR") {
                        result =
                            if (cancelRequested) {
                                cancelled()
                            } else if (responseCode == null) {
                                fail(
                                    "AQSI_NO_PAYMENT_CODE",
                                    "Терминал завершил операцию без кода оплаты (проверьте NCM 192.168.137.1 и Wi‑Fi)",
                                )
                            } else {
                                finishByResponseCode(
                                    code = responseCode!!,
                                    transactionId = transactionId,
                                    amountKopecks = amountKopecks,
                                    elapsedMs = System.currentTimeMillis() - operationStartedAt,
                                )
                            }
                        break
                    }
                }
                if (result == null) {
                    result = timeout("Истёк таймаут оплаты")
                }
            } catch (error: Exception) {
                Timber.e(error, "AQSI Arcus2 payment failed")
                appendExchangeLog("EXCEPTION ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                result = mapUsbException(error)
            } finally {
                val finalResult = result ?: fail("AQSI_EXCEPTION", "Неизвестный результат оплаты")
                if (needsArcusCleanup(finalResult)) {
                    drainArcus2Session(connection, sockets, amountKopecks, finalResult)
                }
                sockets.values.forEach { it.close() }
                activeSockets = null
                connection.close()
                usbConnection.close()
            }
            result ?: fail("AQSI_EXCEPTION", "Неизвестный результат оплаты")
            } finally {
                pillUsbSessionOwner.release(grantedLease)
            }
        }

    private suspend fun findAssignedAqsiDriver(): AssignedDriverResult {
        val assignedName =
            serialPort.assignedDeviceName(PortRole.PAYMENT)
                ?: return AssignedDriverResult.Failure(
                    errorCode = "AQSI_NOT_ASSIGNED",
                    message = "Роль PAYMENT не назначена",
                )
        val driver =
            usbSerialAccess.getAvailableDevices().firstOrNull {
                it.device.deviceName == assignedName
            }
                ?: return AssignedDriverResult.Failure(
                    errorCode = "AQSI_DEVICE_NOT_FOUND",
                    message = "Назначенный порт платёжника не найден: $assignedName",
                )
        if (!AqsiPillUsbIdentifiers.isAqsiPill(driver.device)) {
            return AssignedDriverResult.Failure(
                errorCode = "AQSI_WRONG_DEVICE",
                message =
                    "Назначенное устройство не является AQSI терминалом " +
                        "(vid=%04x pid=%04x)".format(
                            driver.device.vendorId,
                            driver.device.productId,
                        ),
            )
        }
        return AssignedDriverResult.Success(driver)
    }

    private sealed interface AssignedDriverResult {
        data class Success(val driver: UsbSerialDriver) : AssignedDriverResult

        data class Failure(
            val errorCode: String,
            val message: String,
        ) : AssignedDriverResult
    }

    private fun buildPaymentStartPayload(amountKopecks: Int): String {
        val sep = ARCUS_ESC
        return "1$sep" + "1$sep" + "643$sep" + amountKopecks.toString() + "$sep$sep"
    }

    private fun handleArcusCommand(
        command: ArcusCommand,
        sockets: MutableMap<Int, ArcusSocket>,
        amountKopecks: Int,
    ): ByteArray {
        val text = command.data.toString(charset)
        return when (command.name) {
            "PING" -> buildArcusFrame(if (cancelRequested) "ER" else "OK")
            "TRACE", "PRINT", "STATUS", "WARNING", "INFO", "BEGINTR", "STORERC", "DEVICECLOSE" ->
                buildArcusFrame("OK")
            "MENU" -> buildArcusFrame("0")
            "YESNO" -> buildArcusFrame("1")
            "DATAENTRY" -> buildArcusFrame("0$ARCUS_ESC")
            "AMOUNTENTRY" -> buildArcusFrame("0$ARCUS_ESC${amountKopecks}")
            "TIMESYNC" -> buildArcusFrame(SimpleDateFormat("ddMMyyyy${ARCUS_ESC}HH:mm", Locale.US).format(Date()))
            "GETTAGS" -> buildArcusFrame(buildPaymentTags(amountKopecks))
            "SETTAGS", "SPRESULT" -> buildArcusFrame("OK")
            "DEVICEOPEN" -> {
                val handle = 2
                sockets[handle] = ArcusSocket(pillNetworkRouter)
                buildArcusFrame("OK:$handle")
            }
            "IOCTL" -> {
                val parts = text.split(":")
                val handle = parts.getOrNull(0)?.toIntOrNull()
                val readUnits = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val writeUnits = parts.getOrNull(2)?.toIntOrNull() ?: 0
                handle?.let { sockets[it]?.configureIo(readUnits, writeUnits) }
                val appliedMs = handle?.let { sockets[it]?.appliedReadTimeoutMs() }
                appendExchangeLog(
                    "IOCTL handle=$handle read=$readUnits write=$writeUnits appliedReadTimeoutMs=$appliedMs",
                )
                buildArcusFrame("OK")
            }
            "CONNECT" -> {
                if (cancelRequested) {
                    return buildArcusFrame("ER")
                }
                val parts = text.split(":")
                val handle = parts.getOrNull(0)?.toIntOrNull()
                val session = handle?.let { sockets[it] }
                if (session != null) {
                    val targetHost = parts.getOrNull(1).orEmpty()
                    val targetPort = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    session.connect(targetHost, targetPort)
                    val socksMode =
                        when {
                            session.socksEmulationActive -> "wifi-emulation"
                            targetHost == AQSI_PROXY_HOST && targetPort == AQSI_PROXY_PORT -> "direct-or-adb-reverse"
                            else -> "direct"
                        }
                    appendExchangeLog("CONNECT target=$targetHost:$targetPort socks=$socksMode")
                    buildArcusFrame(if (cancelRequested) "ER" else "OK")
                } else {
                    buildArcusFrame("ER")
                }
            }
            "WRITE" -> {
                val separator = command.data.indexOf(':'.code.toByte())
                if (separator < 0) return buildArcusFrame("ER")
                val handle = command.data.copyOfRange(0, separator).toString(charset).toIntOrNull()
                val payload =
                    if (separator >= 0) {
                        command.data.copyOfRange(separator + 1, command.data.size)
                    } else {
                        ByteArray(0)
                    }
                val ok = handle?.let { sockets[it]?.write(payload) } == true
                buildArcusFrame(if (ok) "OK" else "ER")
            }
            "READ" -> {
                val parts = text.split(":")
                val handle = parts.getOrNull(0)?.toIntOrNull()
                val maxLen = parts.getOrNull(1)?.toIntOrNull() ?: 4096
                val data = handle?.let { sockets[it]?.read(maxLen) } ?: ByteArray(0)
                buildArcusFrame((handle ?: 0).toString().toByteArray(charset) + byteArrayOf(':'.code.toByte()) + data)
            }
            "DISCONNECT" -> {
                val handle = text.toIntOrNull()
                handle?.let { sockets.remove(it)?.close() }
                buildArcusFrame("OK")
            }
            else -> buildArcusFrame("OK")
        }
    }

    private fun observeArcusCommand(command: ArcusCommand): AqsiResponseCode? {
        val text = command.data.toString(charset).trim()
        when (command.name) {
            "BEGINTR" -> {
                _stateFlow.value = UsbPaymentStatus.WAITING_FOR_CARD
                _terminalStatusFlow.value = "Приложите карту к терминалу"
            }
            "PING" -> {
                if (_stateFlow.value != UsbPaymentStatus.CANCELLING) {
                    _stateFlow.value = UsbPaymentStatus.WAITING_FOR_CARD
                    _terminalStatusFlow.value = "Ожидание карты на терминале"
                }
            }
            "STATUS" -> applyTerminalStatus(text)
            "WARNING" -> {
                _stateFlow.value = UsbPaymentStatus.WARNING
                _terminalStatusFlow.value = text.ifBlank { "Предупреждение платёжного терминала" }
            }
            "INFO" -> {
                _stateFlow.value = UsbPaymentStatus.PROCESSING
                _terminalStatusFlow.value = text.ifBlank { "Терминал передал информационное сообщение" }
            }
            "PRINT" -> {
                AqsiPaymentCodes.fromReceiptLine(text)?.let { code ->
                    _stateFlow.value = if (code.approved) UsbPaymentStatus.FINALIZING else UsbPaymentStatus.FAILURE
                    _terminalStatusFlow.value =
                        if (code.approved) {
                            "Банк одобрил платёж, завершаем операцию"
                        } else {
                            "${code.message} (код ${code.code})"
                        }
                    appendExchangeLog(
                        "PAYMENT_CODE_FROM_PRINT code=${code.code} approved=${code.approved} message=${code.message}",
                    )
                    return code
                }
            }
            "DEVICEOPEN", "CONNECT" -> {
                _stateFlow.value = UsbPaymentStatus.CONNECTING
                _terminalStatusFlow.value = "Терминал устанавливает связь с банком"
            }
            "WRITE", "READ" -> {
                _stateFlow.value = UsbPaymentStatus.AUTHORIZING
                _terminalStatusFlow.value = "Банк обрабатывает платёж"
            }
            "SPRESULT" -> {
                _stateFlow.value = UsbPaymentStatus.FINALIZING
                _terminalStatusFlow.value = "Получен результат платёжного сценария"
            }
            "STORERC" -> {
                if (text.isBlank()) {
                    _stateFlow.value = UsbPaymentStatus.FINALIZING
                    _terminalStatusFlow.value = "Получаем итоговый код оплаты"
                    appendExchangeLog("PAYMENT_CODE empty STORERC, waiting receipt lines")
                    return null
                }
                val code = AqsiPaymentCodes.interpret(text)
                _stateFlow.value = if (code.approved) UsbPaymentStatus.FINALIZING else UsbPaymentStatus.FAILURE
                _terminalStatusFlow.value =
                    if (code.approved) {
                        "Банк одобрил платёж, завершаем операцию"
                    } else {
                        "${code.message} (код ${code.code})"
                    }
                appendExchangeLog("PAYMENT_CODE code=${code.code} approved=${code.approved} message=${code.message}")
                return code
            }
            "ENDTR" -> {
                _stateFlow.value = UsbPaymentStatus.FINALIZING
                _terminalStatusFlow.value = "Терминал завершает операцию"
            }
        }
        return null
    }

    private fun applyTerminalStatus(rawStatus: String) {
        val normalized = rawStatus.replace(whitespaceRegex, " ").trim()
        val uppercase = normalized.uppercase(Locale("ru", "RU"))
        val nextStatus =
            when {
                uppercase.contains("ПИН") -> UsbPaymentStatus.WAITING_FOR_PIN
                uppercase.contains("КАРТ") || uppercase.contains("ПРИЛОЖ") || uppercase.contains("ПОДНЕС") ->
                    UsbPaymentStatus.WAITING_FOR_CARD
                uppercase.contains("СОЕДИН") || uppercase.contains("СВЯЗ") -> UsbPaymentStatus.CONNECTING
                uppercase.contains("АВТОРИЗ") || uppercase.contains("ОБРАБОТ") || uppercase.contains("ОЖИД") ->
                    UsbPaymentStatus.AUTHORIZING
                else -> UsbPaymentStatus.PROCESSING
            }
        _stateFlow.value = nextStatus
        _terminalStatusFlow.value =
            when (nextStatus) {
                UsbPaymentStatus.WAITING_FOR_CARD -> "Приложите карту к терминалу"
                UsbPaymentStatus.WAITING_FOR_PIN -> "Введите PIN на терминале"
                UsbPaymentStatus.CONNECTING -> "Проверяем связь с банком"
                UsbPaymentStatus.AUTHORIZING -> "Банк обрабатывает платёж"
                else -> normalized.ifBlank { "Терминал выполняет операцию" }
            }
    }

    private fun buildPaymentTags(amountKopecks: Int): ByteArray {
        val amount = amountKopecks.toString().padStart(12, '0')
        return hexToBytes("9F0206$amount" + "5F2A020643")
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun decodeArcusCommand(frame: ByteArray): ArcusCommand? {
        if (frame.size <= 3) return null
        val payload = frame.copyOfRange(3, frame.size)
        val colon = payload.indexOf(':'.code.toByte())
        if (colon <= 0) return null
        val name = payload.copyOfRange(0, colon).toString(Charsets.UTF_8)
        return ArcusCommand(name, payload.copyOfRange(colon + 1, payload.size))
    }

    private fun needsArcusCleanup(result: UsbPaymentResult): Boolean =
        when (result) {
            is UsbPaymentResult.Success -> false
            UsbPaymentResult.Cancelled,
            UsbPaymentResult.Timeout,
            is UsbPaymentResult.Failure -> true
        }

    private fun prepareArcus2Session(
        connection: AqsiSerialLink,
        sockets: MutableMap<Int, ArcusSocket>,
        amountKopecks: Int,
    ) {
        appendExchangeLog("PREPAY_CLEANUP_START")
        val deadline = System.currentTimeMillis() + AQSI_PREPAY_CLEANUP_TIMEOUT_MS
        var sawEndTr = false
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = readFrame(connection, minOf(AQSI_PREPAY_CLEANUP_READ_MS, remaining)) ?: break
            val command = decodeArcusCommand(frame) ?: continue
            appendExchangeLog("PREPAY_RX ${command.name}: ${command.data.toHex()}")
            if (command.name == "ENDTR") {
                sawEndTr = true
                break
            }
            val response = handleArcusCommand(command, sockets, amountKopecks)
            appendExchangeLog("PREPAY_TX ${command.name}: ${response.toHex()}")
            if (!usbWrite(connection, response, timeoutMs = 1_000, label = "PREPAY_TX ${command.name}")) {
                break
            }
        }
        sockets.values.forEach { it.close() }
        sockets.clear()
        appendExchangeLog("PREPAY_CLEANUP_END sawEndTr=$sawEndTr")
    }

    private fun drainArcus2Session(
        connection: AqsiSerialLink,
        sockets: MutableMap<Int, ArcusSocket>,
        amountKopecks: Int,
        result: UsbPaymentResult,
    ) {
        val reason =
            when (result) {
                UsbPaymentResult.Cancelled -> "cancelled"
                UsbPaymentResult.Timeout -> "timeout"
                is UsbPaymentResult.Failure -> result.errorCode
                is UsbPaymentResult.Success -> "success"
            }
        appendExchangeLog("CLEANUP_START reason=$reason")
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + AQSI_CLEANUP_TIMEOUT_MS
        var sawEndTr = false
        var failed = false
        while (System.currentTimeMillis() < deadline) {
            if (failed) break
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame =
                runCatching {
                    readFrame(connection, minOf(AQSI_CLEANUP_READ_TIMEOUT_MS, remaining))
                }.getOrElse { error ->
                    appendExchangeLog("CLEANUP_FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    failed = true
                    null
                } ?: continue
            if (failed) break
            val command = decodeArcusCommand(frame) ?: continue
            appendExchangeLog("CLEANUP_RX ${command.name}: ${command.data.toHex()}")
            val response = handleArcusCommand(command, sockets, amountKopecks)
            appendExchangeLog("CLEANUP_TX ${command.name}: ${response.toHex()}")
            val writeOk =
                runCatching {
                    usbWrite(connection, response, timeoutMs = 1_000, label = "CLEANUP_TX ${command.name}")
                }.getOrDefault(false)
            if (!writeOk) {
                failed = true
                break
            }
            if (command.name == "ENDTR") {
                sawEndTr = true
                break
            }
        }
        sockets.values.forEach { it.close() }
        val elapsedMs = System.currentTimeMillis() - startedAt
        appendExchangeLog("CLEANUP_END sawEndTr=$sawEndTr failed=$failed elapsedMs=$elapsedMs")
    }

    private fun usbWrite(
        connection: AqsiSerialLink,
        data: ByteArray,
        timeoutMs: Int,
        label: String,
    ): Boolean =
        try {
            connection.write(data, timeoutMs = timeoutMs)
            true
        } catch (error: Exception) {
            appendExchangeLog("USB_WRITE_FAILED $label ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            throw error
        }

    private fun mapUsbException(error: Exception): UsbPaymentResult {
        val className = error.javaClass.simpleName
        val message = error.message.orEmpty()
        return when {
            className.contains("SerialTimeout", ignoreCase = true) ->
                fail("AQSI_USB_IO_FAILED", message.ifBlank { className })
            error is IOException && message.contains("write", ignoreCase = true) ->
                fail("AQSI_USB_WRITE_FAILED", message.ifBlank { className })
            error is IOException ->
                fail("AQSI_USB_IO_FAILED", message.ifBlank { className })
            else -> fail("AQSI_EXCEPTION", message.ifBlank { className })
        }
    }

    private fun fail(code: String, message: String): UsbPaymentResult {
        _stateFlow.value = UsbPaymentStatus.FAILURE
        _terminalStatusFlow.value = "AQSI: $message"
        appendExchangeLog("RESULT FAILURE $code $message")
        return UsbPaymentResult.Failure(code, message)
    }

    private fun timeout(message: String): UsbPaymentResult {
        _stateFlow.value = UsbPaymentStatus.TIMEOUT
        _terminalStatusFlow.value = "AQSI: $message"
        appendExchangeLog("RESULT TIMEOUT $message")
        return UsbPaymentResult.Timeout
    }

    private fun cancelled(): UsbPaymentResult {
        _stateFlow.value = UsbPaymentStatus.CANCELLED
        _terminalStatusFlow.value = "Оплата отменена"
        appendExchangeLog("RESULT CANCELLED")
        return UsbPaymentResult.Cancelled
    }

    private fun finishByResponseCode(
        code: AqsiResponseCode,
        transactionId: String,
        amountKopecks: Int,
        elapsedMs: Long,
    ): UsbPaymentResult {
        return if (code.approved) {
            _stateFlow.value = UsbPaymentStatus.SUCCESS
            _terminalStatusFlow.value = "Оплата прошла успешно"
            appendExchangeLog("RESULT SUCCESS code=${code.code}")
            UsbPaymentResult.Success(transactionId, amountKopecks)
        } else if (isCardWaitTimeout(code, elapsedMs)) {
            val message = "Таймаут ожидания карты"
            _stateFlow.value = UsbPaymentStatus.TIMEOUT
            _terminalStatusFlow.value = message
            appendExchangeLog("RESULT TIMEOUT code=${code.code} elapsedMs=$elapsedMs $message")
            UsbPaymentResult.Timeout
        } else {
            val message = "${code.message} (код ${code.code})"
            _stateFlow.value = UsbPaymentStatus.FAILURE
            _terminalStatusFlow.value = message
            appendExchangeLog("RESULT FAILURE AQSI_DECLINED $message")
            UsbPaymentResult.Failure("AQSI_DECLINED_${code.code}", message)
        }
    }

    private fun isCardWaitTimeout(
        code: AqsiResponseCode,
        elapsedMs: Long,
    ): Boolean = code.code == "998" && elapsedMs >= AQSI_CARD_WAIT_TIMEOUT_THRESHOLD_MS

    private fun probeJpayTcp(): UsbPaymentResult? {
        appendExchangeLog("TCP_CONNECT $AQSI_DEFAULT_HOST:$AQSI_JPAY_PORT")
        val socket = Socket()
        return try {
            pillNetworkRouter.connectToPill(socket, AQSI_DEFAULT_HOST, AQSI_JPAY_PORT, 2_000)
            socket.soTimeout = 3_000
            val request = "<testconnection>\n</testconnection>".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            appendExchangeLog("TCP_TX ${request.toString(Charsets.UTF_8).replace("\n", "\\n")}")

            val buffer = ByteArray(1024)
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) {
                appendExchangeLog("TCP_RX empty")
                null
            } else {
                val response = buffer.copyOf(read)
                val text = decodeJpayResponse(response)
                appendExchangeLog("TCP_RX ${response.toHex()} | ${text.replace("\n", "\\n")}")
                _stateFlow.value = UsbPaymentStatus.SUCCESS
                _terminalStatusFlow.value = "AQSI JPAY отвечает: ${text.take(80)}"
                audit.log("INFO", "AQSI", "jpay tcp response ${text.take(120)}")
                UsbPaymentResult.Success("AQSI_JPAY_TCP_PROBE", 0)
            }
        } catch (error: Exception) {
            appendExchangeLog("TCP_FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun describeFailure(result: UsbPaymentResult): String =
        when (result) {
            is UsbPaymentResult.Failure -> result.errorCode
            UsbPaymentResult.Timeout -> "TIMEOUT"
            UsbPaymentResult.Cancelled -> "CANCELLED"
            is UsbPaymentResult.Success -> "SUCCESS"
        }

    private fun probePort(
        driver: UsbSerialDriver,
        portIndex: Int,
        deviceLabel: String,
    ): UsbPaymentResult {
        _terminalStatusFlow.value = "AQSI: открываю $deviceLabel port=$portIndex"
        Log.i(TAG_AQSI, "open $deviceLabel port=$portIndex")
        appendExchangeLog("OPEN port=$portIndex baud=$AQSI_BAUD_RATE")
        val opened =
            usbSerialAccess.openConnection(
                driver = driver,
                portIndex = portIndex,
                config = AqsiSerialConfig(baudRate = AQSI_BAUD_RATE),
            )
        if (opened == null) {
            val msg = "не удалось открыть $deviceLabel port=$portIndex"
            audit.log("ERROR", "AQSI", msg)
            Log.w(TAG_AQSI, msg)
            appendExchangeLog("OPEN_FAIL port=$portIndex $msg")
            return UsbPaymentResult.Failure("AQSI_OPEN_FAILED", msg)
        }

        val (connection, usbConnection) = opened
        return try {
            val initialCommand = buildArcusFrame("3$ARCUS_ESC" + "5$ARCUS_ESC$ARCUS_ESC$ARCUS_ESC")
            connection.write(initialCommand, timeoutMs = 1_000)
            audit.log("INFO", "AQSI", "tx admin-menu probe port=$portIndex len=${initialCommand.size}")
            Log.i(TAG_AQSI, "tx admin-menu probe port=$portIndex len=${initialCommand.size}")
            appendExchangeLog("TX port=$portIndex ${initialCommand.toHex()} | admin menu opclass=3 opcode=5")

            val response = readFrame(connection, AQSI_PROBE_TIMEOUT_MS)
            if (response == null) {
                val msg = "нет ответа от $deviceLabel port=$portIndex за ${AQSI_PROBE_TIMEOUT_MS}мс"
                audit.log("WARN", "AQSI", msg)
                Log.w(TAG_AQSI, msg)
                appendExchangeLog("TIMEOUT port=$portIndex waitMs=$AQSI_PROBE_TIMEOUT_MS")
                return UsbPaymentResult.Timeout
            }

            val command = decodeCommandName(response)
            if (command != null) {
                val ok = buildArcusFrame("OK")
                connection.write(ok, timeoutMs = 1_000)
                appendExchangeLog("TX port=$portIndex ${ok.toHex()} | OK")
            }

            val summary = "обмен OK, port=$portIndex ответ=${command ?: response.toHexHead()}"
            _stateFlow.value = UsbPaymentStatus.SUCCESS
            _terminalStatusFlow.value = "AQSI: $summary"
            audit.log("INFO", "AQSI", "$summary device=$deviceLabel")
            Log.i(TAG_AQSI, "$summary device=$deviceLabel")
            appendExchangeLog("RESULT SUCCESS $summary")
            UsbPaymentResult.Success("AQSI_USB_PROBE:${command ?: "RAW"}", 0)
        } catch (error: Exception) {
            Timber.e(error, "AQSI USB probe failed")
            audit.log("ERROR", "AQSI", "usb probe exception port=$portIndex", error)
            Log.e(TAG_AQSI, "usb probe exception port=$portIndex", error)
            appendExchangeLog("EXCEPTION port=$portIndex ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            UsbPaymentResult.Failure("AQSI_EXCEPTION", error.message ?: error.javaClass.simpleName)
        } finally {
            appendExchangeLog("CLOSE port=$portIndex")
            connection.close()
            usbConnection.close()
        }
    }

    private fun buildArcusFrame(text: String): ByteArray = buildArcusFrame(text.toByteArray(charset))

    private fun buildArcusFrame(payload: ByteArray): ByteArray =
        byteArrayOf(
            ARCUS_STX,
            (payload.size / 256).toByte(),
            (payload.size % 256).toByte(),
        ) + payload

    private fun readFrame(connection: AqsiSerialLink, timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArrayOutputStream()
        while (System.currentTimeMillis() < deadline) {
            val chunk = connection.read(timeoutMs = 250)
            if (chunk != null) {
                appendExchangeLog("RX_CHUNK ${chunk.toHex()}")
                buffer.write(chunk)
                val parsed = tryExtractFrame(buffer.toByteArray())
                if (parsed != null) {
                    appendExchangeLog("RX_FRAME ${parsed.toHex()} | ${decodeCommandName(parsed) ?: "raw"}")
                    return parsed
                }
            }
        }
        return null
    }

    private fun tryExtractFrame(raw: ByteArray): ByteArray? {
        val start = raw.indexOf(ARCUS_STX)
        if (start < 0 || raw.size - start < 3) return null
        val size = ((raw[start + 1].toInt() and 0xFF) * 256) + (raw[start + 2].toInt() and 0xFF)
        val total = 3 + size
        if (raw.size - start < total) return null
        return raw.copyOfRange(start, start + total)
    }

    private fun decodeCommandName(frame: ByteArray): String? {
        if (frame.size <= 3) return null
        val payload = frame.copyOfRange(3, frame.size)
        val colon = payload.indexOf(':'.code.toByte())
        if (colon <= 0) return null
        return runCatching {
            payload.copyOfRange(0, colon).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun resetExchangeLog(firstLine: String) {
        _exchangeLogFlow.value = listOf(formatExchangeLine(firstLine))
    }

    private fun appendExchangeLog(line: String) {
        val next = _exchangeLogFlow.value + formatExchangeLine(line)
        _exchangeLogFlow.value = next.takeLast(80)
        Log.i(TAG_AQSI, line)
    }

    private fun formatExchangeLine(line: String): String = "${logTime.format(Date())}  $line"

    private fun decodeJpayResponse(response: ByteArray): String {
        val payload =
            if (response.size >= 4) {
                val declared =
                    ((response[0].toInt() and 0xFF) shl 24) or
                        ((response[1].toInt() and 0xFF) shl 16) or
                        ((response[2].toInt() and 0xFF) shl 8) or
                        (response[3].toInt() and 0xFF)
                if (declared in 1..(response.size - 4)) response.copyOfRange(4, 4 + declared) else response
            } else {
                response
            }
        return payload.toString(Charsets.UTF_8)
    }

    private fun ByteArray.toHexHead(): String = take(16).joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
