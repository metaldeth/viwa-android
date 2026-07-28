package com.viwa.android.hardware.scanner

import com.viwa.android.domain.model.BarcodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViwaScannerTrafficLoggerTest {
    private val validKey = "REG-0123456789AB"

    @Test
    fun `logger masks plain REG key in stored raw line`() {
        val logger = ViwaScannerTrafficLogger()
        logger.log(validKey, BarcodeEvent.RegistrationKey(validKey))
        val entry = logger.entries.value.single()
        assertEquals("REG-************", entry.rawLine)
        assertFalse(entry.rawLine.contains("0123456789AB"))
    }

    @Test
    fun `logger masks KEY technician key in employee scan line`() {
        val key = "KEY-0123456789ABCDEFGHJK"
        val logger = ViwaScannerTrafficLogger()
        logger.log(key, BarcodeEvent.EmployeeKey(key))
        val entry = logger.entries.value.single()
        assertEquals("KEY-********************", entry.rawLine)
        assertFalse(entry.rawLine.contains("0123456789ABCDEFGHJK"))
    }

    @Test
    fun `logger masks EMP legacy technician key in employee scan line`() {
        val key = "EMP:0123456789ABCDEFGHJK"
        val logger = ViwaScannerTrafficLogger()
        logger.log(key, BarcodeEvent.EmployeeKey(key))
        val entry = logger.entries.value.single()
        assertEquals("EMP:********************", entry.rawLine)
        assertFalse(entry.rawLine.contains("0123456789ABCDEFGHJK"))
    }

    @Test
    fun `logger masks registrationKey in json qr raw line`() {
        val raw =
            """
            {"type":"VIWA_TELEMETRY_REGISTRATION","version":1,"registrationKey":"$validKey"}
            """.trimIndent()
        val logger = ViwaScannerTrafficLogger()
        logger.log(raw, BarcodeEvent.TelemetryRegistrationQr(raw))
        val entry = logger.entries.value.single()
        assertFalse(entry.rawLine.contains(validKey))
        assertFalse(entry.rawLine.contains("0123456789AB"))
    }
}
