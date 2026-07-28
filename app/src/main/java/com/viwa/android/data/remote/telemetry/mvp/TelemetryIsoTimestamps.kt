package com.viwa.android.data.remote.telemetry.mvp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Thread-safe ISO-8601 UTC timestamps (API 25+, no java.time). */
object TelemetryIsoTimestamps {
    private val isoUtc: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

    fun nowUtc(): String = requireNotNull(isoUtc.get()).format(Date())

    fun fromEpochMillis(epochMs: Long): String = requireNotNull(isoUtc.get()).format(Date(epochMs))

    fun parseUtcToEpochMillis(iso: String): Long {
        val patterns =
            listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
            )
        for (pattern in patterns) {
            val parsed =
                runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(iso)
                        ?.time
                }.getOrNull()
            if (parsed != null) return parsed
        }
        error("Invalid ISO timestamp: $iso")
    }
}
