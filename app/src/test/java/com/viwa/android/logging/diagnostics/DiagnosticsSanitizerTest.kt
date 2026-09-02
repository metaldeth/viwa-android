package com.viwa.android.logging.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {
    @Test
    fun sanitizeCrashMessage_redactsBearerAndUrls() {
        val sanitized =
            DiagnosticsSanitizer.sanitizeCrashMessage(
                "failed https://secret.example/path Bearer abcdefghijklmnop",
            )
        assertTrue(sanitized.contains("bearer ***"))
        assertTrue(sanitized.contains("[url]"))
        assertFalse(sanitized.contains("abcdefghijklmnop"))
        assertFalse(sanitized.contains("secret.example"))
    }
}
