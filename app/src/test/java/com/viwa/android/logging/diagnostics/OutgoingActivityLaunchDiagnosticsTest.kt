package com.viwa.android.logging.diagnostics

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutgoingActivityLaunchDiagnosticsTest {
    @Test
    fun sanitize_includesSafeFieldsOnly() {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.viwa.android.fileprovider/ota/secret.apk"),
                    "application/vnd.android.package-archive",
                )
                putExtra("token", "must-not-appear")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val sanitized = OutgoingActivityLaunchDiagnostics.sanitize(intent, "TestCaller")

        assertEquals(Intent.ACTION_VIEW, sanitized.action)
        assertEquals("application/vnd.android.package-archive", sanitized.mimeType)
        assertNull(sanitized.componentPackage)
        assertNull(sanitized.componentClass)
        assertEquals("TestCaller", sanitized.caller)
        assertTrue(sanitized.toLogLine().contains("action=android.intent.action.VIEW"))
        assertFalse(sanitized.toLogLine().contains("secret.apk"))
        assertFalse(sanitized.toLogLine().contains("token"))
        assertFalse(sanitized.toLogLine().contains("content://"))
    }

    @Test
    fun sanitize_includesExplicitComponent() {
        val intent =
            Intent().apply {
                component = ComponentName("com.example.pkg", "com.example.pkg.VideoActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        val sanitized = OutgoingActivityLaunchDiagnostics.sanitize(intent, "BootCompletedReceiver")

        assertEquals("com.example.pkg", sanitized.componentPackage)
        assertEquals("com.example.pkg.VideoActivity", sanitized.componentClass)
    }
}
