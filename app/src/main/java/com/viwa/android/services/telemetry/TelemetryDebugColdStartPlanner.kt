package com.viwa.android.services.telemetry

import com.viwa.android.data.remote.telemetry.mvp.SerialNumberUtils
import com.viwa.android.domain.model.MachineRegistration

/** Pure cold-start decision for DEBUG local.properties bootstrap (unit-testable). */
internal object TelemetryDebugColdStartPlanner {
    enum class Action {
        /** No debug bootstrap — rely on normal enrolled auto-connect. */
        None,

        /** Persisted machineSecret present — reconnect without re-register. */
        ConnectOnly,

        /** Register with local REG key, then connect. */
        RegisterThenConnect,
    }

    data class Input(
        val isDebugBuild: Boolean,
        val autoConnectEnabled: Boolean,
        val configuredSerial: String,
        val configuredRegKey: String,
        val persistedSerial: String,
        val isEnrolled: Boolean,
        val hasStoredSecretForConfiguredSerial: Boolean,
        val hasStoredSecretForPersistedSerial: Boolean,
    )

    fun resolve(input: Input): Action {
        if (!input.isDebugBuild || !input.autoConnectEnabled) return Action.None
        val configuredSerial = SerialNumberUtils.normalize(input.configuredSerial)
        if (configuredSerial.isBlank()) return Action.None

        val persistedSerial = SerialNumberUtils.normalize(input.persistedSerial)
        val secretReady =
            input.hasStoredSecretForConfiguredSerial ||
                (persistedSerial == configuredSerial && input.hasStoredSecretForPersistedSerial)

        if (secretReady) return Action.ConnectOnly

        val regKey = input.configuredRegKey.trim()
        if (!input.isEnrolled || persistedSerial != configuredSerial) {
            return if (regKey.isNotBlank()) Action.RegisterThenConnect else Action.None
        }

        return if (input.isEnrolled) Action.ConnectOnly else Action.None
    }
}
