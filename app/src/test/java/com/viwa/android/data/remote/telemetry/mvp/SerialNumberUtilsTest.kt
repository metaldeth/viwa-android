package com.viwa.android.data.remote.telemetry.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialNumberUtilsTest {
    @Test
    fun `normalize pads digits to VIWA-000001 format`() {
        assertEquals("VIWA-000001", SerialNumberUtils.normalize("viwa-1"))
        assertEquals("VIWA-000001", SerialNumberUtils.normalize("VIWA000001"))
        assertEquals("VIWA-123456", SerialNumberUtils.normalize("  viwa-123456  "))
    }

    @Test
    fun `normalize uppercases test serial without padding`() {
        assertEquals("VIWA-TEST01", SerialNumberUtils.normalize("viwa-test01"))
        assertEquals("VIWA-TEST99", SerialNumberUtils.normalize("VIWATEST99"))
    }

    @Test
    fun `isValid accepts normalized serial`() {
        assertTrue(SerialNumberUtils.isValid("VIWA-000001"))
        assertTrue(SerialNumberUtils.isValid("viwa1"))
    }

    @Test
    fun `isValid accepts test serial format`() {
        assertTrue(SerialNumberUtils.isValid("VIWA-TEST01"))
        assertTrue(SerialNumberUtils.isValid("viwa-test01"))
        assertTrue(SerialNumberUtils.isValid("VIWA-TEST99"))
    }

    @Test
    fun `isValid rejects invalid test serial variants`() {
        assertFalse(SerialNumberUtils.isValid("VIWA-TEST1"))
        assertFalse(SerialNumberUtils.isValid("VIWA-TEST001"))
    }

    @Test
    fun `isValid rejects legacy WIVA prefix`() {
        assertFalse(SerialNumberUtils.isValid("WIVA-000001"))
    }

    @Test
    fun `validationMessage rejects invalid serial`() {
        assertEquals("Введите серийный номер", SerialNumberUtils.validationMessage(""))
        assertNull(SerialNumberUtils.validationMessage("VIWA-000001"))
        assertNull(SerialNumberUtils.validationMessage("VIWA-TEST01"))
        assertEquals("Формат: VIWA-000001 или VIWA-TEST01", SerialNumberUtils.validationMessage("WIVA-000001"))
    }
}
