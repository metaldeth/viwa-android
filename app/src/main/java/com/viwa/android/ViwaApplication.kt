package com.viwa.android

import android.app.Application
import com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer
import com.viwa.android.hardware.FlowStripRgbCoordinator
import com.viwa.android.hardware.scanner.ViwaScannerStartupInitializer
import com.viwa.android.hardware.serial.ViwaSerialDiscovery
import com.viwa.android.logging.AppLogFileStore
import com.viwa.android.logging.RotatingFileTimberTree
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class ViwaApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("unused")
    @Inject
    lateinit var flowStripRgbCoordinator: FlowStripRgbCoordinator

    @Inject
    lateinit var aqsiPaymentStartupInitializer: AqsiPaymentStartupInitializer

    @Inject
    lateinit var scannerStartupInitializer: ViwaScannerStartupInitializer

    @Inject
    lateinit var serialDiscovery: ViwaSerialDiscovery

    @Inject
    lateinit var appLogFileStore: AppLogFileStore

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.plant(RotatingFileTimberTree(appLogFileStore))
        installUncaughtExceptionLogging()
        appScope.launch {
            val devices = serialDiscovery.availableDevices()
            Timber.tag("ViwaSerial").i(
                "startup discovery: count=%d paths=%s",
                devices.size,
                devices.joinToString { it.deviceName },
            )
        }
        aqsiPaymentStartupInitializer.start()
        scannerStartupInitializer.start()
    }

    private fun installUncaughtExceptionLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception thread=%s", thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
