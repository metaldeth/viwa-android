package com.viwa.android.domain.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OtaBackendErrorsTest {
    @Test
    fun `treats jwt unavailable as transient`() {
        val error = IllegalStateException("Machine JWT unavailable")
        assertTrue(OtaBackendErrors.isTransient(error))
    }

    @Test
    fun `treats http 502 as transient`() {
        assertTrue(OtaBackendErrors.isTransient(OtaHttpException.fromStatus(502)))
    }

    @Test
    fun `treats transport io exception as transient`() {
        assertTrue(OtaBackendErrors.isTransient(OtaDownloadTransportException("timeout")))
        assertTrue(OtaBackendErrors.isTransient(IOException("connection reset")))
    }

    @Test
    fun `does not treat sha mismatch as transient`() {
        val error =
            OtaDownloadIntegrityException(
                OtaDownloadIntegrityReason.SHA256_MISMATCH,
                "Downloaded SHA-256 mismatch",
            )
        assertFalse(OtaBackendErrors.isTransient(error))
    }

    @Test
    fun `does not treat http 403 as transient`() {
        assertFalse(OtaBackendErrors.isTransient(OtaHttpException.fromStatus(403)))
    }

    @Test
    fun `does not treat apk verification as transient`() {
        assertFalse(OtaBackendErrors.isTransient(OtaApkVerificationError.HashMismatch()))
    }

    @Test
    fun `does not treat manifest verification as transient`() {
        assertFalse(OtaBackendErrors.isTransient(IllegalStateException("manifest signature invalid")))
    }

    @Test
    fun `maps public ota check 404 to russian user message`() {
        val message =
            OtaBackendErrors.userMessage(
                OtaHttpException.fromStatus(404, OtaHttpException.PUBLIC_OTA_CHECK_UNAVAILABLE),
            )
        assertTrue(message.contains("Публичный endpoint обновлений недоступен"))
        assertTrue(message.contains("Сервер обновлений ещё не обновлён"))
    }

    @Test
    fun `maps jwt unavailable to user friendly message`() {
        val message =
            OtaBackendErrors.userMessage(IllegalStateException("Machine JWT unavailable"))
        assertTrue(message.contains("временно недоступен"))
    }

    @Test
    fun `maps http 502 to user friendly message`() {
        val message = OtaBackendErrors.userMessage(OtaHttpException.fromStatus(502))
        assertTrue(message.contains("временно недоступен"))
    }
}
