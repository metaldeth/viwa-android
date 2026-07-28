package com.viwa.android.data.network

import com.viwa.android.domain.technician.TechnicianKeyTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrafficRedactorTest {
    private val technicianKey = TechnicianKeyTestFixtures.NORMALIZED_KEY
    private val legacyKey = TechnicianKeyTestFixtures.LEGACY_INPUT

    @Test
    fun `masks technician key in WS validate outbound payload`() {
        val raw =
            """
            {"type":"technician.key.validate","payload":{"code":"$technicianKey","requestedScope":"service.menu","requestUuid":"880e8400-e29b-41d4-a716-446655440001"}}
            """.trimIndent()
        val redacted = redactNetworkPayload(raw, "technician.key.validate")
        assertFalse(redacted.contains(technicianKey))
        assertTrue(redacted.contains("\"code\":\"***\""))
        assertTrue(redacted.contains("service.menu"))
    }

    @Test
    fun `masks technician key in REST validate request body`() {
        val raw =
            """
            {"code":"$legacyKey","requestedScope":"service.menu","requestUuid":"880e8400-e29b-41d4-a716-446655440002"}
            """.trimIndent()
        val redacted = redactNetworkPayload(raw)
        assertFalse(redacted.contains(legacyKey))
        assertTrue(redacted.contains("\"code\":\"***\""))
    }

    @Test
    fun `preserves unrelated error code enums in diagnostics`() {
        val raw = """{"code":"KEY_REVOKED","reason":"denied"}"""
        val redacted = redactNetworkPayload(raw)
        assertEquals(raw, redacted)
    }

    @Test
    fun `masks plaintext technician key outside JSON`() {
        val raw = "scan=$technicianKey channel=OFFLINE"
        val redacted = redactNetworkPayload(raw)
        assertFalse(redacted.contains(technicianKey))
        assertTrue(redacted.contains("KEY-********************"))
    }

    @Test
    fun `preserves unrelated registrationKey redaction alongside technician code`() {
        val raw =
            """
            {"registrationKey":"REG-0123456789AB","code":"$technicianKey"}
            """.trimIndent()
        val redacted = redactNetworkPayload(raw)
        assertFalse(redacted.contains(technicianKey))
        assertFalse(redacted.contains("REG-0123456789AB"))
        assertTrue(redacted.contains("\"registrationKey\":\"***\""))
    }
}
