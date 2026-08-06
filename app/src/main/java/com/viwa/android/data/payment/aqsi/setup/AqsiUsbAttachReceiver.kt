package com.viwa.android.data.payment.aqsi.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.viwa.android.data.payment.aqsi.setup.AqsiSetupEntryPoint
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import dagger.hilt.android.EntryPointAccessors
import timber.log.Timber

/** Re-runs AQSI plug-and-play setup when Pill USB is attached at runtime. */
class AqsiUsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.readUsbDevice() ?: return
        if (!AqsiPillUsbIdentifiers.isAqsiPill(device)) return

        Timber.tag(TAG).i("AQSI USB attached: %s", device.deviceName)
        val pending = goAsync()
        try {
            val initializer =
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AqsiSetupEntryPoint::class.java,
                ).aqsiPaymentStartupInitializer()
            initializer.onUsbAttach { pending.finish() }
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "AQSI USB attach setup failed")
            pending.finish()
        }
    }

    private fun Intent.readUsbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "AQSI_SETUP"
    }
}
