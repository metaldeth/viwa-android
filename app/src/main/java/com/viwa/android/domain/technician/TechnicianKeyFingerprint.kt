package com.viwa.android.domain.technician

import java.security.MessageDigest

/** SHA-256 hex of normalized key — parity with backend `technicianKeyFingerprint`. */
object TechnicianKeyFingerprint {
    fun fingerprint(normalizedKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(normalizedKey.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun fingerprintFromInput(rawInput: String): String? {
        val normalized = TechnicianKeyNormalizer.normalize(rawInput)
        if (!TechnicianKeyNormalizer.isValidFormat(normalized)) return null
        return fingerprint(normalized)
    }
}
