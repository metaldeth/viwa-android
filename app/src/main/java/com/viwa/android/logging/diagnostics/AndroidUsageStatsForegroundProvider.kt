package com.viwa.android.logging.diagnostics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class AndroidUsageStatsForegroundProvider
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : UsageStatsForegroundProvider {
    private val ownPackage: String = context.packageName

    override fun usageAccessStatus(): UsageAccessStatus =
        runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return UsageAccessStatus.QUERY_ERROR
            @Suppress("DEPRECATION")
            val mode =
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ownPackage,
                )
            if (mode == AppOpsManager.MODE_ALLOWED) {
                UsageAccessStatus.GRANTED
            } else {
                UsageAccessStatus.DENIED
            }
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "usage access check failed")
            UsageAccessStatus.QUERY_ERROR
        }

    override fun latestForegroundEvent(
        windowMs: Long,
        sinceMs: Long,
        externalOnly: Boolean,
    ): ForegroundUsageObservation? {
        if (usageAccessStatus() != UsageAccessStatus.GRANTED) return null
        return runCatching {
            val manager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return null
            val endMs = System.currentTimeMillis()
            val startMs = (endMs - windowMs).coerceAtLeast(0L)
            val usageEvents = manager.queryEvents(startMs, endMs) ?: return null
            val rawEvents = mutableListOf<RawUsageEvent>()
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                rawEvents +=
                    RawUsageEvent(
                        eventType = event.eventType,
                        packageName = event.packageName,
                        className = event.className,
                        timeStamp = event.timeStamp,
                    )
            }
            UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                events = rawEvents,
                sdkInt = Build.VERSION.SDK_INT,
                sinceMs = sinceMs,
                ownPackage = ownPackage,
                externalOnly = externalOnly,
            )
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "usage events query failed")
            null
        }
    }

    companion object {
        private const val TAG = "ViwaDiag"
    }
}
