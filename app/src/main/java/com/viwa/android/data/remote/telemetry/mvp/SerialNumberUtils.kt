package com.viwa.android.data.remote.telemetry.mvp

/** Валидация и нормализация серийного номера автомата (VIWA-000001 или VIWA-TEST01). */
object SerialNumberUtils {
    private val SERIAL_PATTERN = Regex("^VIWA-(?:\\d{6}|TEST\\d{2})$", RegexOption.IGNORE_CASE)
    private val TEST_SERIAL_LOOSE = Regex("^VIWA-?TEST(\\d{2})$", RegexOption.IGNORE_CASE)

    fun normalize(input: String): String {
        val trimmed = input.trim().uppercase()
        if (trimmed.isEmpty()) return trimmed
        val compact = trimmed.replace(Regex("[\\s_]"), "-")

        TEST_SERIAL_LOOSE.find(compact)?.let { match ->
            return "VIWA-TEST${match.groupValues[1]}"
        }

        val match = Regex("VIWA-?(\\d+)").find(compact) ?: return compact
        val digits = match.groupValues[1].padStart(6, '0')
        return "VIWA-$digits"
    }

    fun isValid(input: String): Boolean = SERIAL_PATTERN.matches(normalize(input))

    fun validationMessage(input: String): String? =
        when {
            input.isBlank() -> "Введите серийный номер"
            !isValid(input) -> "Формат: VIWA-000001 или VIWA-TEST01"
            else -> null
        }
}
