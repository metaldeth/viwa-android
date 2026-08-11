package com.viwa.android.services.telemetry

import android.content.Intent
import com.viwa.android.BuildConfig
import com.viwa.android.data.remote.telemetry.mvp.SerialAlreadyBoundException
import com.viwa.android.data.remote.telemetry.mvp.SerialNumberUtils
import com.viwa.android.domain.model.MachineRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * DEBUG-only telemetry bootstrap:
 * - adb intent extras from [MainActivity] (register + connect without service menu)
 * - [maybeAutoConnectOnColdStart] from `local.properties` / env (see AGENTS.md)
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

    /**
     * DEBUG cold start using BuildConfig from `telemetry.debug.*` in local.properties.
     * Prefers reconnect when [MachineSecretStore] already has a secret for the bench serial.
     */
    suspend fun maybeAutoConnectOnColdStart(telemetryService: ViwaTelemetryService) {
        if (!BuildConfig.DEBUG) return
        val configuredSerial = BuildConfig.TELEMETRY_DEBUG_SERIAL.trim()
        val configuredRegKey = BuildConfig.TELEMETRY_DEBUG_REG_KEY.trim()
        val persistedReg = telemetryService.loadMachineRegistration()
        val action =
            TelemetryDebugColdStartPlanner.resolve(
                TelemetryDebugColdStartPlanner.Input(
                    isDebugBuild = true,
                    autoConnectEnabled = BuildConfig.TELEMETRY_DEBUG_AUTO_CONNECT,
                    configuredSerial = configuredSerial,
                    configuredRegKey = configuredRegKey,
                    persistedSerial = persistedReg.serialNumber,
                    isEnrolled = MachineRegistration.isEnrolled(persistedReg),
                    hasStoredSecretForConfiguredSerial =
                        telemetryService.hasStableSecret(configuredSerial),
                    hasStoredSecretForPersistedSerial =
                        telemetryService.hasStableSecret(persistedReg.serialNumber),
                ),
            )
        when (action) {
            TelemetryDebugColdStartPlanner.Action.None -> Unit
            TelemetryDebugColdStartPlanner.Action.ConnectOnly -> {
                Timber.i("TelemetryDebugBootstrap: cold-start connect-only serial=$configuredSerial")
                telemetryService.connect()
            }
            TelemetryDebugColdStartPlanner.Action.RegisterThenConnect -> {
                val serial = SerialNumberUtils.normalize(configuredSerial)
                Timber.i("TelemetryDebugBootstrap: cold-start register+connect serial=$serial")
                runBootstrap(telemetryService, configuredRegKey, serial, shouldRegister = true)
            }
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
                            error,
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
