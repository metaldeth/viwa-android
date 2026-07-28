package com.viwa.android.services.telemetry

import com.viwa.android.domain.model.BarcodeEvent
import com.viwa.android.hardware.scanner.ScannerManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryRegistrationScannerCoordinatorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `scan event fills registration key and serial from json qr`() = runTest {
        val flow = MutableSharedFlow<BarcodeEvent>(extraBufferCapacity = 4)
        val scannerManager = mockk<ScannerManager>()
        every { scannerManager.barcodeFlow } returns flow

        val coordinator = TelemetryRegistrationScannerCoordinator(scannerManager, backgroundScope)
        val deferred = async { coordinator.scanEvents.first() }
        advanceUntilIdle()

        flow.emit(
            BarcodeEvent.TelemetryRegistrationQr(
                """
                {"type":"VIWA_TELEMETRY_REGISTRATION","version":1,"registrationKey":"REG-0123456789AB","serialNumber":"VIWA-000004","apiUrl":"https://tl.vitamin-water.ru"}
                """.trimIndent(),
            ),
        )
        advanceUntilIdle()

        val event = deferred.await()
        assertEquals("REG-0123456789AB", event.registrationKey)
        assertEquals("VIWA-000004", event.serialNumber)
        assertEquals("https://tl.vitamin-water.ru", event.apiUrl)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `unknown barcode is ignored`() = runTest {
        val flow = MutableSharedFlow<BarcodeEvent>(extraBufferCapacity = 4)
        val scannerManager = mockk<ScannerManager>()
        every { scannerManager.barcodeFlow } returns flow

        val coordinator = TelemetryRegistrationScannerCoordinator(scannerManager, backgroundScope)
        var received: TelemetryRegistrationScanUiEvent? = null
        val job = launch { coordinator.scanEvents.collect { received = it } }
        advanceUntilIdle()

        flow.emit(BarcodeEvent.UnknownBarcode("REG-0123456789AB"))
        advanceUntilIdle()

        assertNull(received)
        job.cancel()
    }
}
