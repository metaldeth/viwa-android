package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.domain.model.TelemetryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUrlValidatorTest {
    @Test
    fun `validateStrict accepts https origin and normalizes default port`() {
        val result = TelemetryUrlValidator.validateStrict("https://tl.vitamin-water.ru")
        assertTrue(result is TelemetryUrlValidator.Result.Valid)
        assertEquals("https://tl.vitamin-water.ru", (result as TelemetryUrlValidator.Result.Valid).normalizedOrigin)
    }

    @Test
    fun `validateStrict normalizes explicit port 443`() {
        val result = TelemetryUrlValidator.validateStrict("https://tl.vitamin-water.ru:443")
        assertTrue(result is TelemetryUrlValidator.Result.Valid)
        assertEquals("https://tl.vitamin-water.ru", (result as TelemetryUrlValidator.Result.Valid).normalizedOrigin)
    }

    @Test
    fun `validateStrict rejects http file and path`() {
        assertTrue(TelemetryUrlValidator.validateStrict("http://tl.vitamin-water.ru") is TelemetryUrlValidator.Result.Invalid)
        assertTrue(TelemetryUrlValidator.validateStrict("file:///etc/passwd") is TelemetryUrlValidator.Result.Invalid)
        assertTrue(TelemetryUrlValidator.validateStrict("https://tl.vitamin-water.ru/api/v1") is TelemetryUrlValidator.Result.Invalid)
    }

    @Test
    fun `validateStrict rejects userinfo query fragment`() {
        assertTrue(
            TelemetryUrlValidator.validateStrict("https://user:pass@tl.vitamin-water.ru") is
                TelemetryUrlValidator.Result.Invalid,
        )
        assertTrue(
            TelemetryUrlValidator.validateStrict("https://tl.vitamin-water.ru?x=1") is TelemetryUrlValidator.Result.Invalid,
        )
        assertTrue(
            TelemetryUrlValidator.validateStrict("https://tl.vitamin-water.ru#frag") is TelemetryUrlValidator.Result.Invalid,
        )
    }

    @Test
    fun `validateTrustedCandidate accepts matching persisted host and port`() {
        val result =
            TelemetryUrlValidator.validateTrustedCandidate(
                "https://tl.vitamin-water.ru",
                TelemetryConfig.DEFAULT_API_URL,
            )
        assertTrue(result is TelemetryUrlValidator.Result.Valid)
    }

    @Test
    fun `validateTrustedCandidate rejects different host`() {
        val result =
            TelemetryUrlValidator.validateTrustedCandidate(
                "https://evil.example.com",
                TelemetryConfig.DEFAULT_API_URL,
            )
        assertTrue(result is TelemetryUrlValidator.Result.Invalid)
        assertTrue((result as TelemetryUrlValidator.Result.Invalid).reason.contains("другой сервер"))
    }
}
