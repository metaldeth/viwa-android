package com.viwa.android.data.payment.aqsi.setup

import com.viwa.android.data.payment.aqsi.network.AqsiPillHostNetworkBootstrap
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.hardware.serial.PaymentSerialDeviceInfo
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.PortRole
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AqsiPortAutoDiscoveryTest {
    private class FakePaymentSerialPort(
        private val devices: List<PaymentSerialDeviceInfo>,
        private val assignments: MutableMap<String, PortRole> = mutableMapOf(),
    ) : PaymentSerialPort {
        override suspend fun availableDevices(): List<PaymentSerialDeviceInfo> = devices

        override suspend fun assignments(): Map<String, PortRole> = assignments.toMap()

        override suspend fun assign(deviceName: String, role: PortRole): Result<Unit> {
            assignments.entries.removeAll { it.value == role }
            assignments[deviceName] = role
            return Result.success(Unit)
        }

        override suspend fun assignedDeviceName(role: PortRole): String? =
            assignments.entries.firstOrNull { it.value == role }?.key
    }

    private fun usbDevice(
        name: String,
        vendorId: Int = AqsiPillUsbIdentifiers.VENDOR_ID,
        productId: Int = AqsiPillUsbIdentifiers.PRODUCT_ID,
        driverType: String = "CdcAcmSerialDriver",
    ) = PaymentSerialDeviceInfo(
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        driverType = driverType,
    )

    @Test
    fun discoverReturnsAqsiUsbDevicePath() =
        runTest {
            val aqsi = usbDevice("/dev/bus/usb/005/005")
            val serialPort =
                FakePaymentSerialPort(
                    devices =
                        listOf(
                            usbDevice("/dev/ttyS0", vendorId = 1, productId = 2, driverType = "TtySerial"),
                            aqsi,
                        ),
                )
            val discovery = AqsiPortAutoDiscovery(serialPort)

            assertEquals("/dev/bus/usb/005/005", discovery.discover())
        }

    @Test
    fun discoverReturnsNullWhenNoAqsiUsbPresent() =
        runTest {
            val serialPort =
                FakePaymentSerialPort(
                    devices =
                        listOf(
                            usbDevice("/dev/bus/usb/001/001", vendorId = 0x1234, productId = 0x5678),
                        ),
                )
            val discovery = AqsiPortAutoDiscovery(serialPort)

            assertNull(discovery.discover())
            assertFalse(discovery.hasCandidates())
        }

    @Test
    fun discoverIgnoresNonUsbPaths() =
        runTest {
            val serialPort =
                FakePaymentSerialPort(
                    devices = listOf(usbDevice("/dev/ttyUSB0")),
                )
            val discovery = AqsiPortAutoDiscovery(serialPort)

            assertNull(discovery.discover())
        }

    @Test
    fun discoverExcludesAlreadyAssignedPorts() =
        runTest {
            val aqsi = usbDevice("/dev/bus/usb/005/005")
            val serialPort =
                FakePaymentSerialPort(
                    devices = listOf(aqsi),
                    assignments = mutableMapOf(aqsi.deviceName to PortRole.SCANNER),
                )
            val discovery = AqsiPortAutoDiscovery(serialPort)

            assertNull(discovery.discover())
        }

    @Test
    fun aqsiPaymentStartupInitializerAssignsDiscoveredPaymentPort() =
        runTest {
            val aqsi = usbDevice("/dev/bus/usb/005/005")
            val serialPort = FakePaymentSerialPort(devices = listOf(aqsi))
            val aqsiDevice =
                mockk<UsbDevice> {
                    every { vendorId } returns AqsiPillUsbIdentifiers.VENDOR_ID
                    every { productId } returns AqsiPillUsbIdentifiers.PRODUCT_ID
                    every { deviceName } returns aqsi.deviceName
                }
            val driver =
                mockk<UsbSerialDriver> {
                    every { device } returns aqsiDevice
                }
            val usbAccess = mockk<AqsiUsbSerialAccess> {
                every { getAvailableDevices() } returns listOf(driver)
            }
            val hostNetworkBootstrap = mockk<AqsiPillHostNetworkBootstrap>(relaxed = true)
            val initializer =
                AqsiPaymentStartupInitializer(
                    serialPort = serialPort,
                    portAutoDiscovery = AqsiPortAutoDiscovery(serialPort),
                    usbSerialAccess = usbAccess,
                    hostNetworkBootstrap = hostNetworkBootstrap,
                    ioScope = this,
                )

            assertTrue(initializer.assignIfNeeded())
            assertEquals(PortRole.PAYMENT, serialPort.assignments()[aqsi.deviceName])
        }

    @Test
    fun isUsbSerialPathMatchesAndroidUsbDevicePath() {
        assertTrue(AqsiPortAutoDiscovery.isUsbSerialPath("/dev/bus/usb/001/002"))
        assertFalse(AqsiPortAutoDiscovery.isUsbSerialPath("/dev/ttyS0"))
    }
}
