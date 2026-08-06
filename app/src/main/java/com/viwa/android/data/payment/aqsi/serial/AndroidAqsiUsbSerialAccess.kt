package com.viwa.android.data.payment.aqsi.serial

import android.content.Context
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
class AndroidAqsiUsbSerialAccess
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : AqsiUsbSerialAccess {
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val serialProber: UsbSerialProber =
        UsbSerialProber(
            ProbeTable().apply {
                addProduct(
                    AqsiPillUsbIdentifiers.VENDOR_ID,
                    AqsiPillUsbIdentifiers.PRODUCT_ID,
                    CdcAcmSerialDriver::class.java,
                )
            },
        )

    override fun getAvailableDevices(): List<UsbSerialDriver> {
        val manager = usbManager ?: return emptyList()
        return try {
            val defaultDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val aqsiDrivers = serialProber.findAllDrivers(manager)
            (defaultDrivers + aqsiDrivers).distinctBy {
                "${it.device.deviceId}:${it.device.vendorId}:${it.device.productId}"
            }
        } catch (error: Exception) {
            Timber.e(error, "getAvailableDevices failed")
            emptyList()
        }
    }

    override fun openConnection(
        driver: UsbSerialDriver,
        portIndex: Int,
        config: AqsiSerialConfig,
    ): Pair<AqsiSerialLink, android.hardware.usb.UsbDeviceConnection>? {
        val manager = usbManager ?: return null
        val usbConnection = manager.openDevice(driver.device) ?: return null
        val port = driver.ports.getOrNull(portIndex) ?: run {
            usbConnection.close()
            return null
        }
        val session = AqsiSerialSession(port, config)
        return try {
            session.open(usbConnection)
            session to usbConnection
        } catch (error: Exception) {
            Timber.e(error, "Failed to open serial port")
            usbConnection.close()
            null
        }
    }
}
