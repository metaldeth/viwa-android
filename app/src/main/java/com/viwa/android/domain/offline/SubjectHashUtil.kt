package com.viwa.android.domain.offline

import java.security.MessageDigest

object SubjectHashUtil {
    fun normalizeClientId(clientId: String): String = clientId.trim().lowercase()

    /** PII-free wire identity: SHA-256 hex of normalized client UUID (matches backend). */
    fun computeSubjectHash(clientId: String): String {
        val normalized = normalizeClientId(clientId)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { b ->
            "%02x".format(b)
        }
    }
}
