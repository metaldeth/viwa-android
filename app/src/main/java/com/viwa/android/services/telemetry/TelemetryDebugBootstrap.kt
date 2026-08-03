package com.viwa.android.services.telemetry

import android.content.Intent
import com.viwa.android.BuildConfig
import com.viwa.android.data.remote.telemetry.mvp.SerialAlreadyBoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * DEBUG-only adb bootstrap: register + connect telemetry from [MainActivity] intent extras
 * without service-menu keyboard.
 */
object TelemetryDebugBootstrap {
    const val EXTRA_REG_KEY = "telemetry_debug_reg_key"
    const val EXTRA_SERIAL = "telemetry_debug_serial"
    const val EXTRA_REGISTER = "telemetry_debug_register"

    fun consumeAndRun(
        intent: Intent?,
        telemetryService: ViwaTelemetryService,
        scope: CoroutineScope,
    ) {
        android.util.Log.i(TAG, "consumeAndRun debug=${BuildConfig.DEBUG} intent=${intent != null}")
        if (!BuildConfig.DEBUG) return
        val source = intent ?: return

        val regKey = source.getStringExtra(EXTRA_REG_KEY)?.trim().orEmpty()
        val serial = source.getStringExtra(EXTRA_SERIAL)?.trim().orEmpty()
        android.util.Log.i(TAG, "extras serial=$serial regKeyLen=${regKey.length}")
        if (regKey.isBlank() || serial.isBlank()) return

        val shouldRegister = source.getBooleanExtra(EXTRA_REGISTER, true)
        source.removeExtra(EXTRA_REG_KEY)
        source.removeExtra(EXTRA_SERIAL)
        source.removeExtra(EXTRA_REGISTER)

        scope.launch {
            android.util.Log.i(TAG, "launch register=$shouldRegister serial=$serial")
            runBootstrap(telemetryService, regKey, serial, shouldRegister)
        }
    }

    private const val TAG = "TelemetryDebugBootstrap"

    private suspend fun runBootstrap(
        telemetryService: ViwaTelemetryService,
        regKey: String,
        serial: String,
        shouldRegister: Boolean,
    ) {
        if (shouldRegister) {
            telemetryService
                .registerMachine(regKey, serial)
                .onSuccess {
                    telemetryService.connect()
                    Timber.i("TelemetryDebugBootstrap: registered+connect serial=$serial")
                }.onFailure { error ->
                    val conflict =
                        error as? SerialAlreadyBoundException
                            ?: error.cause as? SerialAlreadyBoundException
                    if (conflict != null) {
                        Timber.e(
                            "TelemetryDebugBootstrap: serial ${conflict.serialNumber} already bound " +
                                "to another installation — enable allow-rebind in web panel " +
                                "(or pm clear app data for a fresh installationId, then retry)",
                        )
                    } else {
                        Timber.e(error, "TelemetryDebugBootstrap: register failed serial=$serial")
                    }
                }
        } else {
            telemetryService.connect()
            Timber.i("TelemetryDebugBootstrap: connect-only (register skipped) serial=$serial")
        }
    }
}
