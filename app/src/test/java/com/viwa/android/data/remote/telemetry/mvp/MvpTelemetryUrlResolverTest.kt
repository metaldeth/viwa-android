package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.domain.model.TelemetryConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MvpTelemetryUrlResolverTest {
    private val defaultApi = TelemetryConfig.DEFAULT_API_URL
    private val expectedDerived = "wss://tl.vitamin-water.ru/api/v1/machines/ws"
    private val legacyWs = "ws://185.46.8.39:8315/ws"

    @Test
    fun `resolve prefers enrolled ws url`() {
        assertEquals(
            "wss://telemetry.example/ws",
            MvpTelemetryUrlResolver.resolveWsUrl(
                apiBaseUrl = "https://telemetry.example",
                enrolledWsUrl = "wss://telemetry.example/ws",
                configuredWsUrl = legacyWs,
            ),
        )
    }

    @Test
    fun `resolve builds wss path from https api base`() {
        assertEquals(
            "wss://telemetry.example/api/v1/machines/ws",
            MvpTelemetryUrlResolver.resolveWsUrl(
                apiBaseUrl = "https://telemetry.example",
                enrolledWsUrl = null,
                configuredWsUrl = "",
            ),
        )
    }

    @Test
    fun `resolve ignores legacy ws and derives from api base`() {
        assertEquals(
            expectedDerived,
            MvpTelemetryUrlResolver.resolveWsUrl(
                apiBaseUrl = defaultApi,
                enrolledWsUrl = legacyWs,
                configuredWsUrl = legacyWs,
            ),
        )
    }

    @Test
    fun `display ws url replaces legacy stored value with derived mvp ws`() {
        assertEquals(
            expectedDerived,
            MvpTelemetryUrlResolver.displayWsUrl(defaultApi, legacyWs),
        )
    }

    @Test
    fun `display ws url keeps explicit non-legacy configured url`() {
        assertEquals(
            "wss://custom.example/ws",
            MvpTelemetryUrlResolver.displayWsUrl(defaultApi, "wss://custom.example/ws"),
        )
    }
}
