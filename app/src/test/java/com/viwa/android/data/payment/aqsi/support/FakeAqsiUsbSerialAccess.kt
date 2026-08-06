package com.viwa.android.data.payment.aqsi.support

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialConfig
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialLink
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import io.mockk.every
import io.mockk.mockk

class FakeAqsiUsbSerialAccess(
    private val drivers: List<UsbSerialDriver>,
    private val sessionFactory: () -> FakeAqsiSerialSession,
) : AqsiUsbSerialAccess {
    override fun getAvailableDevices(): List<UsbSerialDriver> = drivers

    override fun openConnection(
        driver: UsbSerialDriver,
        portIndex: Int,
        config: AqsiSerialConfig,
    ): Pair<AqsiSerialLink, UsbDeviceConnection>? {
        val session = sessionFactory()
        val usbConnection = mockk<UsbDeviceConnection>(relaxed = true)
        return session to usbConnection
    }

    companion object {
        fun aqsiDriver(deviceName: String = "aqsi-test"): UsbSerialDriver {
            val device =
                mockk<android.hardware.usb.UsbDevice> {
                    every { vendorId } returns AqsiPillUsbIdentifiers.VENDOR_ID
                    every { productId } returns AqsiPillUsbIdentifiers.PRODUCT_ID
                    every { this@mockk.deviceName } returns deviceName
                }
            return mockk<UsbSerialDriver>(relaxed = true) {
                every { this@mockk.device } returns device
                every { ports } returns listOf(mockk(relaxed = true))
            }
        }
    }
}
