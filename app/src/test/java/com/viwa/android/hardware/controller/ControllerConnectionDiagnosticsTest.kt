package com.viwa.android.hardware.controller

import com.viwa.android.logging.diagnostics.ControllerCommandDiagnosticsListener
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerConnectionDiagnosticsTest {
    private class RecordingDiagnostics : ControllerCommandDiagnosticsListener {
        val events = CopyOnWriteArrayList<String>()
        private var opCounter = 0L

        override fun nextOpId(): Long = ++opCounter

        override fun onOpBegin(
            opId: Long,
            cmdCode: Int,
        ) {
            events.add("begin:$opId:${cmdCode}")
        }

        override fun onOpWritten(
            opId: Long,
            cmdCode: Int,
            writeMs: Long,
            mock: Boolean,
        ) {
            events.add("written:$opId:${cmdCode}:$writeMs:mock=$mock")
        }

        override fun onOpFailed(
            opId: Long,
            cmdCode: Int,
            errorType: String,
        ) {
            events.add("failed:$opId:${cmdCode}:$errorType")
        }
    }

    private class ThrowingTransport : ControllerSerialTransport {
        override var isOpen: Boolean = true
            private set

        var writeStarted = false
        var writeFinished = false

        override suspend fun open(settings: ControllerPortSettings): Boolean {
            isOpen = true
            return true
        }

        override fun close() {
            isOpen = false
        }

        override suspend fun write(bytes: ByteArray) {
            writeStarted = true
            throw IOException("transport write failed")
        }

        override fun setOnBytesReceived(listener: ((ByteArray) -> Unit)?) = Unit
    }

    @Test
    fun writtenMarkerEmittedOnlyAfterTransportWriteReturns() =
        runBlocking {
            val protocol = ControllerProtocol()
            val transport = MockControllerSerialTransport()
            val diagnostics = RecordingDiagnostics()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val conn =
                ControllerConnection(
                    devicePath = "/dev/ttyUSB0",
                    baudRate = 9600,
                    protocol = protocol,
                    transport = transport,
                    connectionScope = scope,
                    onCommandLog = {},
                    onNotConnected = {},
                    onPortNotFound = {},
                    emitResponse = { _, _ -> },
                    commandDiagnostics = diagnostics,
                )
            conn.init()

            conn.sendCommand(RequestCommand.ReadFirmwareVersion, ControllerConstants.DEFAULT_BODY)

            val writtenIndex = diagnostics.events.indexOfFirst { it.startsWith("written:") }
            val beginIndex = diagnostics.events.indexOfFirst { it.startsWith("begin:") }
            assertTrue(beginIndex >= 0)
            assertTrue(writtenIndex > beginIndex)
            assertTrue(diagnostics.events[writtenIndex].contains("mock=false"))
        }

    @Test
    fun mockPath_emitsWrittenCompletionMarker() =
        runBlocking {
            val diagnostics = RecordingDiagnostics()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val conn =
                ControllerConnection(
                    devicePath = ControllerConstants.MOCK_CONTROLLER_PATH,
                    baudRate = 9600,
                    protocol = ControllerProtocol(),
                    transport = MockControllerSerialTransport(),
                    connectionScope = scope,
                    onCommandLog = {},
                    onNotConnected = {},
                    onPortNotFound = {},
                    emitResponse = { _, _ -> },
                    commandDiagnostics = diagnostics,
                )
            conn.init()
            conn.sendCommand(RequestCommand.WaterPourByTouch, ControllerConstants.DEFAULT_BODY)

            assertTrue(diagnostics.events.any { it.startsWith("written:") && it.contains("mock=true") })
        }

    @Test
    fun writeFailure_emitsFailedMarkerAndRethrows() =
        runBlocking {
            val protocol = ControllerProtocol()
            val transport = ThrowingTransport()
            val diagnostics = RecordingDiagnostics()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val conn =
                ControllerConnection(
                    devicePath = "/dev/ttyUSB0",
                    baudRate = 9600,
                    protocol = protocol,
                    transport = transport,
                    connectionScope = scope,
                    onCommandLog = {},
                    onNotConnected = {},
                    onPortNotFound = {},
                    emitResponse = { _, _ -> },
                    commandDiagnostics = diagnostics,
                )
            conn.init()

            var thrown: Throwable? = null
            try {
                conn.sendCommand(RequestCommand.WaterPourByTouch, ControllerConstants.DEFAULT_BODY)
            } catch (error: IOException) {
                thrown = error
            }

            assertTrue(thrown is IOException)
            assertTrue(transport.writeStarted)
            assertEquals(
                1,
                diagnostics.events.count { it.startsWith("failed:") && it.contains("IOException") },
            )
            assertEquals(0, diagnostics.events.count { it.startsWith("written:") })
        }
}
