package com.viwa.android.ui.screens.service.devices

import com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer
import com.viwa.android.hardware.devices.ViwaControllerMockModePort
import com.viwa.android.hardware.devices.ViwaDeviceRuntimeDiscovery
import com.viwa.android.hardware.scanner.ViwaScannerStartupInitializer
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.hardware.serial.SerialDeviceInfo
import com.viwa.android.hardware.serial.SerialPortAssignmentEvents
import com.viwa.android.hardware.serial.ViwaSerialPort
import com.viwa.android.services.payment.PillUsbOwner
import com.viwa.android.services.payment.PillUsbSessionOwnerImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesBlockControllerPaymentBusyTest {
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setup() {
        Dispatchers.setMain(executor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun paymentStatusIsBusyWhenPillSessionHeldWithoutProbeOpen() =
        runBlocking(Dispatchers.Main) {
            val aqsiPath = "/dev/bus/usb/005/005"
            val aqsiDevice =
                SerialDeviceInfo(
                    deviceName = aqsiPath,
                    vendorId = AqsiPillUsbIdentifiers.VENDOR_ID,
                    productId = AqsiPillUsbIdentifiers.PRODUCT_ID,
                    driverType = "CdcAcmSerialDriver",
                )
            val serialPort = mockk<ViwaSerialPort>(relaxed = true)
            coEvery { serialPort.availableDevices() } returns listOf(aqsiDevice)
            coEvery { serialPort.assignments() } returns mapOf(aqsiPath to PortRole.PAYMENT)
            coEvery { serialPort.assignedDeviceName(PortRole.PAYMENT) } returns aqsiPath
            val startupInitializer = mockk<AqsiPaymentStartupInitializer>(relaxed = true)
            coEvery { startupInitializer.assignIfNeeded() } returns false
            val scannerInitializer = mockk<ViwaScannerStartupInitializer>(relaxed = true)
            coEvery { scannerInitializer.assignIfNeeded() } returns false
            val sessionOwner = PillUsbSessionOwnerImpl()
            sessionOwner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "card pay")
            val controller =
                DevicesBlockController(
                    serialPort = serialPort,
                    scannerPort = mockk(relaxed = true),
                    controllerPortProbe = mockk(relaxed = true),
                    controllerMockModePort =
                        mockk<ViwaControllerMockModePort>(relaxed = true).also { port ->
                            coEvery { port.isMockEnabled() } returns false
                        },
                    controllerHardware = mockk(relaxed = true),
                    assignmentEvents = SerialPortAssignmentEvents(),
                    deviceRuntimeDiscovery = mockk(relaxed = true),
                    aqsiPaymentStartupInitializer = startupInitializer,
                    scannerStartupInitializer = scannerInitializer,
                    pillUsbSessionOwner = sessionOwner,
                    scope = CoroutineScope(coroutineContext + SupervisorJob()),
                )

            repeat(32) {
                yield()
            }

            val status = controller.state.value.paymentStatus
            assertTrue("Expected Busy but was $status", status is PaymentDeviceStatus.Busy)
            assertEquals(aqsiPath, (status as PaymentDeviceStatus.Busy).deviceName)
            coVerify(exactly = 0) { serialPort.probeOpen(any()) }
        }
}
