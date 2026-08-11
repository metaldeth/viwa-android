package com.viwa.android.services.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryDebugColdStartPlannerTest {
    private fun input(
        autoConnect: Boolean = true,
        configuredSerial: String = "VIWA-TEST01",
        configuredRegKey: String = "REG-dev",
        persistedSerial: String = "",
        isEnrolled: Boolean = false,
        hasSecretConfigured: Boolean = false,
        hasSecretPersisted: Boolean = false,
    ): TelemetryDebugColdStartPlanner.Input =
        TelemetryDebugColdStartPlanner.Input(
            isDebugBuild = true,
            autoConnectEnabled = autoConnect,
            configuredSerial = configuredSerial,
            configuredRegKey = configuredRegKey,
            persistedSerial = persistedSerial,
            isEnrolled = isEnrolled,
            hasStoredSecretForConfiguredSerial = hasSecretConfigured,
            hasStoredSecretForPersistedSerial = hasSecretPersisted,
        )

    @Test
    fun `release or autoConnect off yields none`() {
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.None,
            TelemetryDebugColdStartPlanner.resolve(
                input(autoConnect = false),
            ),
        )
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.None,
            TelemetryDebugColdStartPlanner.resolve(
                input().copy(isDebugBuild = false),
            ),
        )
    }

    @Test
    fun `stored secret prefers connect only`() {
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.ConnectOnly,
            TelemetryDebugColdStartPlanner.resolve(
                input(hasSecretConfigured = true, isEnrolled = false),
            ),
        )
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.ConnectOnly,
            TelemetryDebugColdStartPlanner.resolve(
                input(
                    persistedSerial = "VIWA-TEST01",
                    hasSecretPersisted = true,
                    isEnrolled = true,
                ),
            ),
        )
    }

    @Test
    fun `not enrolled without secret registers when reg key present`() {
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.RegisterThenConnect,
            TelemetryDebugColdStartPlanner.resolve(input()),
        )
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.None,
            TelemetryDebugColdStartPlanner.resolve(input(configuredRegKey = "")),
        )
    }

    @Test
    fun `enrolled without secret connects when serial matches`() {
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.ConnectOnly,
            TelemetryDebugColdStartPlanner.resolve(
                input(
                    persistedSerial = "VIWA-TEST01",
                    isEnrolled = true,
                ),
            ),
        )
    }

    @Test
    fun `serial mismatch without secret registers when reg key present`() {
        assertEquals(
            TelemetryDebugColdStartPlanner.Action.RegisterThenConnect,
            TelemetryDebugColdStartPlanner.resolve(
                input(
                    persistedSerial = "VIWA-000099",
                    isEnrolled = true,
                ),
            ),
        )
    }
}
