package com.viwa.android.data.network

private const val TECHNICIAN_KEY_VALIDATE_WS_TYPE = "technician.key.validate"

private val jsonSecretPatterns =
    listOf(
        Regex("""\"extApiToken\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"kassaToken\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"secret\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"machineSecret\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"registrationKey\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"key\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"authorization\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"accessToken\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
        Regex("""\"token\"\s*:\s*\"[^\"]*\"""", RegexOption.IGNORE_CASE),
    )

private val xmlSecretPatterns =
    listOf(
        Regex("""<kassatoken>[^<]*</kassatoken>""", RegexOption.IGNORE_CASE),
        Regex("""<key>[^<]*</key>""", RegexOption.IGNORE_CASE),
        Regex("""<sign>[^<]*</sign>""", RegexOption.IGNORE_CASE),
        Regex("""<secret>[^<]*</secret>""", RegexOption.IGNORE_CASE),
        Regex("""<token>[^<]*</token>""", RegexOption.IGNORE_CASE),
    )

/** Plaintext technician keys (KEY-* / EMP:*) — must never appear in diagnostics. */
private val technicianKeyPlainPattern =
    Regex("""(?:KEY-|EMP:)[0-9A-HJKMNP-TV-Z]{20}""", RegexOption.IGNORE_CASE)

/** JSON `code` field when value is a technician key body, not an error enum like KEY_REVOKED. */
private val jsonTechnicianKeyCodePattern =
    Regex("""(\"code\"\s*:\s*\")(?:KEY-|EMP:)[^\"]*(\")""", RegexOption.IGNORE_CASE)

internal fun maskTechnicianKeyPlaintext(text: String): String =
    technicianKeyPlainPattern.replace(text) { match ->
        val raw = match.value
        val prefix =
            if (raw.uppercase().startsWith("EMP:")) {
                "EMP:"
            } else {
                "KEY-"
            }
        prefix + "*".repeat(20)
    }

fun redactNetworkPayload(text: String, messageType: String? = null): String {
    var out = text
    val isTechnicianValidate =
        messageType == TECHNICIAN_KEY_VALIDATE_WS_TYPE ||
            text.contains("\"type\":\"$TECHNICIAN_KEY_VALIDATE_WS_TYPE\"") ||
            text.contains("\"type\": \"$TECHNICIAN_KEY_VALIDATE_WS_TYPE\"")
    if (isTechnicianValidate) {
        out =
            jsonTechnicianKeyCodePattern.replace(out) { m ->
                "${m.groupValues[1]}***${m.groupValues[2]}"
            }
    }
    out = maskTechnicianKeyPlaintext(out)
    jsonSecretPatterns.forEach { rx ->
        out = rx.replace(out) { m ->
            val raw = m.value
            val key = raw.substringBefore(':')
            "$key:\"***\""
        }
    }
    xmlSecretPatterns.forEach { rx ->
        out = rx.replace(out) { m ->
            val tag = m.value.substringAfter('<').substringBefore('>')
            "<$tag>***</$tag>"
        }
    }
    if (!isTechnicianValidate) {
        out =
            jsonTechnicianKeyCodePattern.replace(out) { m ->
                "${m.groupValues[1]}***${m.groupValues[2]}"
            }
    }
    return out
}

fun redactHeaderValue(name: String, value: String): String {
    val lower = name.lowercase()
    return when {
        lower == "authorization" -> "***"
        lower.contains("token") -> "***"
        lower.contains("secret") -> "***"
        lower == "cookie" -> "***"
        else -> value
    }
}
