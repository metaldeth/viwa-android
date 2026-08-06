package com.viwa.android.data.payment.aqsi.setup

import com.viwa.android.hardware.serial.PaymentSerialDeviceInfo
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Auto-assign [PortRole.PAYMENT] to AQSI Pill USB serial (VID 0x0FB9 / PID 0x2606).
 */
@Singleton
class AqsiPortAutoDiscovery
@Inject
constructor(
    private val serialPort: PaymentSerialPort,
) {
    suspend fun hasCandidates(): Boolean = candidateDevices().isNotEmpty()

    /** @return USB path of AQSI Pill or null when absent / ambiguous. */
    suspend fun discover(): String? {
        val candidates = candidateDevices()
        if (candidates.isEmpty()) {
            Timber.tag(TAG).i("AQSI auto-discovery: no USB candidates")
            return null
        }
        val path =
            when (candidates.size) {
                1 -> candidates.single().deviceName
                else -> {
                    Timber.tag(TAG).w(
                        "AQSI auto-discovery: multiple Pill devices (%d), using first %s",
                        candidates.size,
                        candidates.first().deviceName,
                    )
                    candidates.first().deviceName
                }
            }
        Timber.tag(TAG).i("AQSI auto-discovery: match on %s", path)
        return path
    }

    internal suspend fun candidateDevices(): List<PaymentSerialDeviceInfo> {
        val assignments = serialPort.assignments()
        return serialPort
            .availableDevices()
            .filter { isUsbSerialPath(it.deviceName) }
            .filter { AqsiPillUsbIdentifiers.isAqsiPill(it) }
            .filter { device ->
                val role = assignments[device.deviceName]
                role == null || role == PortRole.UNASSIGNED || role == PortRole.UNKNOWN
            }
            .sortedWith(compareBy { it.deviceName })
    }

    internal companion object {
        private const val TAG = "AQSI_SETUP"

        fun isUsbSerialPath(deviceName: String): Boolean = deviceName.startsWith("/dev/bus/usb/")
    }
}
