package com.viwa.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryConfigTest {
    @Test
    fun `normalize clears legacy shaker ws url`() {
        val stored =
            TelemetryConfig(
                apiUrl = TelemetryConfig.DEFAULT_API_URL,
                wsUrl = "ws://185.46.8.39:8315/ws",
            )
        val normalized = TelemetryConfig.normalize(stored)
        assertEquals("", normalized.wsUrl)
    }

    @Test
    fun `normalize migrates deprecated asnefedov api and ws to defaults`() {
        val stored =
            TelemetryConfig(
                apiUrl = "https://tl.asnefedov.ru",
                wsUrl = "wss://tl.asnefedov.ru/api/v1/machines/ws",
            )
        val normalized = TelemetryConfig.normalize(stored)
        assertEquals(TelemetryConfig.DEFAULT_API_URL, normalized.apiUrl)
        assertEquals("", normalized.wsUrl)
    }

    @Test
    fun `migrateTokenEndpoint rewrites deprecated absolute url`() {
        assertEquals(
            TelemetryConfig.DEFAULT_TOKEN_URL,
            TelemetryConfig.migrateTokenEndpoint(
                "https://tl.asnefedov.ru/api/v1/machines/token",
            ),
        )
        assertEquals(
            TelemetryConfig.DEFAULT_TOKEN_ENDPOINT,
            TelemetryConfig.migrateTokenEndpoint("/api/v1/machines/token"),
        )
    }

    @Test
    fun `sanitizeWsUrl keeps custom ws url`() {
        val custom = "wss://example.com/api/v1/machines/ws"
        assertEquals(custom, TelemetryConfig.sanitizeWsUrl(custom))
    }
}
