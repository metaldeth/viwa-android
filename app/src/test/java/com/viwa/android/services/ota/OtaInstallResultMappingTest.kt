package com.viwa.android.services.ota

import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaInstallResultMappingTest {
    @Test
    fun `pending user action with confirmation intent waits for user`() {
        val confirm = Intent(Intent.ACTION_VIEW)
        val action = OtaInstallResultMapping.mapStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm)

        assertFalse(action.deliverToHandler)
        assertEquals(confirm, action.confirmationIntent)
    }

    @Test
    fun `pending user action without confirmation intent fails`() {
        val action = OtaInstallResultMapping.mapStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null, null)

        assertTrue(action.deliverToHandler)
        assertEquals(PackageInstaller.STATUS_FAILURE, action.handlerStatus)
        assertEquals(OtaInstallResultMapping.MISSING_CONFIRMATION_MESSAGE, action.handlerMessage)
        assertNull(action.confirmationIntent)
    }

    @Test
    fun `terminal install status is delivered to handler`() {
        val action = OtaInstallResultMapping.mapStatus(PackageInstaller.STATUS_FAILURE, "boom", null)

        assertTrue(action.deliverToHandler)
        assertEquals(PackageInstaller.STATUS_FAILURE, action.handlerStatus)
        assertEquals("boom", action.handlerMessage)
    }
}
