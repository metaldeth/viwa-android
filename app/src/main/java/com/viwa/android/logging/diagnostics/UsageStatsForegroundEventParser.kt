package com.viwa.android.logging.diagnostics

import android.app.usage.UsageEvents
import android.os.Build

object UsageStatsForegroundEventParser {
    /** Runtime event type on API 25–28; do not rely on deprecated [UsageEvents.Event.MOVE_TO_FOREGROUND] stub. */
    private const val EVENT_MOVE_TO_FOREGROUND = 1

    internal fun isForegroundResumeEvent(
        eventType: Int,
        sdkInt: Int,
    ): Boolean =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            eventType == EVENT_MOVE_TO_FOREGROUND
        }

    fun sanitizePackage(raw: String?): String? {
        val value = raw?.trim()?.take(PACKAGE_LIMIT) ?: return null
        if (value.isEmpty()) return null
        return DiagnosticsSanitizer.sanitize(value, PACKAGE_LIMIT)
    }

    fun sanitizeClass(raw: String?): String {
        val value = raw?.trim()?.take(CLASS_LIMIT)
        if (value.isNullOrEmpty()) return UNKNOWN_CLASS
        return DiagnosticsSanitizer.sanitize(value, CLASS_LIMIT)
    }

    internal fun pickLatestForegroundEvent(
        events: List<RawUsageEvent>,
        sdkInt: Int,
        sinceMs: Long,
        ownPackage: String,
        externalOnly: Boolean,
    ): ForegroundUsageObservation? {
        var latest: ForegroundUsageObservation? = null
        for (event in events) {
            if (!isForegroundResumeEvent(event.eventType, sdkInt)) continue
            if (event.timeStamp < sinceMs) continue
            val pkg = sanitizePackage(event.packageName) ?: continue
            if (externalOnly && isOwnPackage(pkg, ownPackage)) continue
            val cls = sanitizeClass(event.className)
            val candidate =
                ForegroundUsageObservation(
                    packageName = pkg,
                    className = cls,
                    observedAtMs = event.timeStamp,
                )
            if (latest == null || candidate.observedAtMs >= latest.observedAtMs) {
                latest = candidate
            }
        }
        return latest
    }

    /** True only for Viwa itself; all other packages (Settings, launcher, OEM Video, etc.) are logged. */
    fun isOwnPackage(
        pkg: String,
        ownPackage: String,
    ): Boolean = pkg == ownPackage

    private const val PACKAGE_LIMIT = 120
    private const val CLASS_LIMIT = 160
    const val UNKNOWN_CLASS = "unknown"
}
