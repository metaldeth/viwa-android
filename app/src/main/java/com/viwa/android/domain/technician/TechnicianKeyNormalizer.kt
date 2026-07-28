package com.viwa.android.domain.technician

/** Normalize scanned/plain input exactly like backend `normalizeTechnicianKey`. */
object TechnicianKeyNormalizer {
    fun normalize(input: String): String {
        val trimmed = input.trim().uppercase().replace("\\s+".toRegex(), "")
        return if (trimmed.startsWith(TechnicianKeyConstants.LEGACY_PREFIX)) {
            TechnicianKeyConstants.KEY_PREFIX + trimmed.removePrefix(TechnicianKeyConstants.LEGACY_PREFIX)
        } else {
            trimmed
        }
    }

    fun isValidFormat(key: String): Boolean =
        Regex(TechnicianKeyConstants.KEY_PATTERN).matches(normalize(key))
}
