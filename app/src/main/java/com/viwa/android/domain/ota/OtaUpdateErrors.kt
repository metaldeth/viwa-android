package com.viwa.android.domain.ota

import java.io.IOException

/** HTTP failure with structured status (check/download telemetry API). */
class OtaHttpException(
    val statusCode: Int,
    override val message: String,
) : Exception(message) {
    companion object {
        private val HTTP_CODE = Regex("""HTTP\s+(\d{3})""")

        /** Detail for public OTA check 404 — mapped to Russian userMessage in [OtaBackendErrors]. */
        const val PUBLIC_OTA_CHECK_UNAVAILABLE = "Public OTA check endpoint unavailable"

        fun fromStatus(statusCode: Int, detail: String = ""): OtaHttpException {
            val suffix = if (detail.isNotBlank()) ": $detail" else ""
            return OtaHttpException(statusCode, "HTTP $statusCode$suffix")
        }

        fun parseStatusCode(message: String): Int? = HTTP_CODE.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

enum class OtaDownloadIntegrityReason {
    SHA256_MISMATCH,
    SIZE_MISMATCH,
    MAX_SIZE_EXCEEDED,
    CONTENT_LENGTH_MISMATCH,
    EMPTY_BODY,
}

/** Terminal download integrity/protocol failure — not retried. */
class OtaDownloadIntegrityException(
    val reason: OtaDownloadIntegrityReason,
    message: String,
) : Exception(message)

/** Transport-level I/O (socket/timeout) — retried; not used for integrity failures. */
class OtaDownloadTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
