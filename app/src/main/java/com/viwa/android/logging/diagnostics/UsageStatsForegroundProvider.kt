package com.viwa.android.logging.diagnostics

interface UsageStatsForegroundProvider {
    fun usageAccessStatus(): UsageAccessStatus

    fun latestForegroundEvent(
        windowMs: Long,
        sinceMs: Long = 0L,
        externalOnly: Boolean = true,
    ): ForegroundUsageObservation?
}
