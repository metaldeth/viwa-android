package com.viwa.android.data.payment.aqsi.setup

import com.viwa.android.data.payment.aqsi.network.AqsiPillHostNetworkBootstrap
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import com.viwa.android.di.AqsiIoScope
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.PortRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Plug-and-play AQSI Pill setup: assign PAYMENT serial port and configure NCM host link.
 */
@Singleton
class AqsiPaymentStartupInitializer
@Inject
constructor(
    private val serialPort: PaymentSerialPort,
    private val portAutoDiscovery: AqsiPortAutoDiscovery,
    private val usbSerialAccess: AqsiUsbSerialAccess,
    private val hostNetworkBootstrap: AqsiPillHostNetworkBootstrap,
    @AqsiIoScope private val ioScope: CoroutineScope,
) {
    private val setupMutex = Mutex()

    fun start() {
        ioScope.launch { runSetup() }
    }

    fun onUsbAttach(onFinished: () -> Unit = {}) {
        ioScope.launch {
            try {
                runSetup()
            } finally {
                onFinished()
            }
        }
    }

    suspend fun assignIfNeeded(): Boolean {
        if (serialPort.assignedDeviceName(PortRole.PAYMENT) != null) {
            return false
        }
        if (!isAqsiPhysicallyPresent()) {
            Timber.tag(TAG).d("AQSI assign skipped: USB device not present")
            return false
        }
        if (!portAutoDiscovery.hasCandidates()) {
            Timber.tag(TAG).d("AQSI assign skipped: no serial port candidates")
            return false
        }
        val path = portAutoDiscovery.discover() ?: return false
        return serialPort.assign(path, PortRole.PAYMENT).fold(
            onSuccess = {
                Timber.tag(TAG).i("AQSI PAYMENT auto-assigned to %s", path)
                true
            },
            onFailure = { error ->
                Timber.tag(TAG).w(error, "AQSI PAYMENT auto-assign failed for %s", path)
                false
            },
        )
    }

    suspend fun runSetup() {
        setupMutex.withLock {
            if (!isAqsiPhysicallyPresent()) {
                Timber.tag(TAG).d("AQSI setup skipped: no USB device")
                return
            }
            assignIfNeeded()
            val networkStatus = hostNetworkBootstrap.runWhenPillPresent()
            Timber.tag(TAG).i(
                "AQSI setup complete: ncm=%s wifiBound=%s wifiProbe=%s ready=%s",
                networkStatus.ncmReady,
                networkStatus.wifiProcessBound,
                networkStatus.wifiInternetProbe,
                networkStatus.readyForJpay,
            )
        }
    }

    private fun isAqsiPhysicallyPresent(): Boolean =
        usbSerialAccess.getAvailableDevices().any { driver ->
            AqsiPillUsbIdentifiers.isAqsiPill(driver.device)
        }

    companion object {
        private const val TAG = "AQSI_SETUP"
    }
}
