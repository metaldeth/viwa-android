package com.viwa.android.hardware.serial

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.viwa.android.hardware.NativeSerialPortDetector
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Driver label for onboard UART ports (aligned with hybrid [TTY_SERIAL_DRIVER_TYPE]). */
const val NATIVE_UART_DRIVER_LABEL: String = "Native UART"

/**
 * Merges native `/dev/tty*` and USB serial discovery like shaker
 * [com.shaker.hardware.serial.CompositeSerialDiscovery] + [AndroidSerialDiscovery].
 */
@Singleton
class ViwaSerialDiscovery
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val aqsiPillProber: UsbSerialProber by lazy {
        UsbSerialProber(
            ProbeTable().apply {
                addProduct(
                    AqsiPillUsbIdentifiers.VENDOR_ID,
                    AqsiPillUsbIdentifiers.PRODUCT_ID,
                    CdcAcmSerialDriver::class.java,
                )
            },
        )
    }

    fun availableDevices(): List<SerialDeviceInfo> {
        val nativeDevices = nativeTtyDevices()
        val nativeNames = nativeDevices.map { it.deviceName }.toSet()
        val mgr = usbManager
        if (mgr == null) {
            Timber.tag(TAG).w("UsbManager unavailable; returning %d native tty nodes", nativeDevices.size)
            return nativeDevices.sortedBy { it.deviceName }
        }
        return try {
            val usbDrivers = enumerateUsbDrivers(mgr)
            val usbDriverNames = usbDrivers.map { it.deviceName }.toSet()
            val usbWithoutDriver =
                mgr.deviceList.values
                    .filter { device -> device.deviceName !in nativeNames && device.deviceName !in usbDriverNames }
                    .map { device ->
                        SerialDeviceInfo(
                            deviceName = device.deviceName,
                            vendorId = device.vendorId,
                            productId = device.productId,
                            driverType = null,
                        )
                    }
            val merged =
                (nativeDevices + usbDrivers + usbWithoutDriver)
                    .distinctBy { it.deviceName }
                    .sortedWith(compareBy({ it.vendorId }, { it.productId }, { it.deviceName }))
            Timber.tag(TAG).i(
                "availableDevices native=%d usbDrivers=%d usbOther=%d total=%d",
                nativeDevices.size,
                usbDrivers.size,
                usbWithoutDriver.size,
                merged.size,
            )
            merged
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "USB serial discovery failed; returning native only")
            nativeDevices.sortedBy { it.deviceName }
        }
    }

    private fun enumerateUsbDrivers(mgr: UsbManager): List<SerialDeviceInfo> {
        val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
        val aqsiDrivers = aqsiPillProber.findAllDrivers(mgr)
        return (defaultDrivers + aqsiDrivers)
            .distinctBy { "${it.device.deviceId}:${it.device.vendorId}:${it.device.productId}" }
            .map { it.toDeviceInfo() }
    }

    private fun UsbSerialDriver.toDeviceInfo(): SerialDeviceInfo =
        SerialDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            driverType = javaClass.simpleName,
        )

    private fun nativeTtyDevices(): List<SerialDeviceInfo> =
        NativeSerialPortDetector.detectPortPaths().map { path ->
            SerialDeviceInfo(
                deviceName = path,
                vendorId = 0,
                productId = 0,
                driverType = NATIVE_UART_DRIVER_LABEL,
            )
        }

    private companion object {
        private const val TAG = "ViwaSerialDiscovery"
    }
}
