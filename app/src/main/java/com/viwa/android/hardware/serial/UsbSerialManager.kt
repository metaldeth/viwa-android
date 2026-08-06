package com.viwa.android.hardware.serial

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class UsbSerialManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val serialDiscovery: ViwaSerialDiscovery,
) {
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun getAvailableDevices(): List<UsbSerialDriver> {
        val mgr = usbManager ?: return emptyList()
        return try {
            val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
            val aqsiDrivers = aqsiPillProber.findAllDrivers(mgr)
            (defaultDrivers + aqsiDrivers).distinctBy {
                "${it.device.deviceId}:${it.device.vendorId}:${it.device.productId}"
            }
        } catch (e: Exception) {
            Timber.e(e, "getAvailableDevices failed")
            emptyList()
        }
    }

    /** All attached USB devices (with or without a serial driver in the probe table). */
    fun getConnectedUsbDevices(): List<UsbDevice> {
        val mgr = usbManager ?: return emptyList()
        return try {
            mgr.deviceList.values.toList()
        } catch (e: Exception) {
            Timber.e(e, "getConnectedUsbDevices failed")
            emptyList()
        }
    }

    /** Полный список serial-портов для UI «Устройства» (native TTY + USB serial). */
    fun enumerateSerialDevices(): List<SerialDeviceInfo> = serialDiscovery.availableDevices()

    private companion object {
        /** AQSI Pill T7100 (VID 0x0FB9 / PID 0x2606) is not in the default usb-serial probe table. */
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
    }

    fun openConnection(driver: UsbSerialDriver, portIndex: Int = 0): Pair<SerialConnection, UsbDeviceConnection>? {
        val mgr = usbManager ?: return null
        val usbConnection = mgr.openDevice(driver.device) ?: return null
        val port = driver.ports[portIndex]
        val connection = SerialConnection(port)
        return try {
            connection.open(usbConnection)
            Pair(connection, usbConnection)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open serial port")
            usbConnection.close()
            null
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    fun requestPermission(device: UsbDevice, pendingIntent: PendingIntent) {
        usbManager?.requestPermission(device, pendingIntent)
    }
}
