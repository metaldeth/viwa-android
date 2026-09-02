package com.viwa.android.logging.diagnostics

object DiagnosticsSanitizer {
    private val BEARER_PATTERN =
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-+/=]{8,}""")
    private val URL_PATTERN =
        Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val CONTROL_CHARS = Regex("""[\u0000-\u001F\u007F]+""")

    fun sanitize(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): String =
        text
            .replace(BEARER_PATTERN, "bearer ***")
            .replace(URL_PATTERN, "[url]")
            .replace(CONTROL_CHARS, " ")
            .trim()
            .take(maxLength)

    fun sanitizeCrashMessage(message: String): String = sanitize(message, CRASH_MESSAGE_LIMIT)

    private const val DEFAULT_MAX_LENGTH = 160
    private const val CRASH_MESSAGE_LIMIT = 240
}
