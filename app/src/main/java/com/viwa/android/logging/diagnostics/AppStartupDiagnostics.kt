package com.viwa.android.logging.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.viwa.android.di.AppIoScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class AppStartupDiagnostics
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
    private val foregroundDestinationDiagnostics: ForegroundDestinationDiagnostics,
    @AppIoScope private val ioScope: CoroutineScope,
) {
    fun scheduleColdStartReport() {
        ioScope.launch {
            reportOnColdStart()
        }
    }

    internal fun reportOnColdStart() {
        val previous = breadcrumbStore.readPreviousSnapshot()
        if (previous != null) {
            Timber.tag(TAG).i(
                "startup.prev_snapshot boot=%s updatedAt=%s route=%s pour=%s/%s ctrl=%s/%s stalled=%s trailSize=%d crash=%s lastFg=%s/%s",
                previous.bootSessionId,
                previous.updatedAt,
                previous.screenRoute,
                previous.pourId ?: "-",
                previous.pourPhase ?: "-",
                previous.lastControllerCmd ?: "-",
                previous.lastControllerOpPhase ?: "-",
                previous.mainThreadStalled,
                previous.trail.size,
                previous.lastCrashType ?: "-",
                previous.lastForegroundPackage ?: "-",
                previous.lastForegroundClass ?: "-",
            )
        } else {
            Timber.tag(TAG).i("startup.prev_snapshot none")
        }

        val bootSessionId = UUID.randomUUID().toString().substring(0, 12)
        breadcrumbStore.startBootSession(bootSessionId)
        foregroundDestinationDiagnostics.resetForBootSession()
        foregroundDestinationDiagnostics.reportStartupPreviousExternal(previous?.updatedAt)

        val bootCount = readBootCount()
        Timber.tag(TAG).i(
            "startup.boot bootSessionId=%s uptimeMs=%d bootCount=%s",
            bootSessionId,
            SystemClock.elapsedRealtime(),
            bootCount?.toString() ?: "unknown",
        )

        reportProcessExitReasons(previous?.lastReportedExitTimestamp)
    }

    internal fun readBootCount(): Int? =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()

    internal fun reportProcessExitReasons(lastReportedExitTimestamp: Long?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.tag(TAG).d("startup.exit_reason skipped api=%d", Build.VERSION.SDK_INT)
            return
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager == null) {
            Timber.tag(TAG).w("startup.exit_reason unavailable service=null")
            return
        }
        val packageName = context.packageName
        val reasons =
            runCatching {
                activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)
            }.getOrElse { error ->
                Timber.tag(TAG).w(error, "startup.exit_reason query_failed")
                return
            }
        if (reasons.isEmpty()) {
            Timber.tag(TAG).i("startup.exit_reason none")
            return
        }
        val latest = reasons.maxByOrNull { it.timestamp } ?: return
        if (lastReportedExitTimestamp != null && latest.timestamp <= lastReportedExitTimestamp) {
            Timber.tag(TAG).d("startup.exit_reason already_reported ts=%d", latest.timestamp)
            return
        }
        val description = DiagnosticsSanitizer.sanitize(latest.description?.toString().orEmpty())
        Timber.tag(TAG).i(
            "startup.exit_reason reason=%d status=%d ts=%d importance=%d desc=%s",
            latest.reason,
            latest.status,
            latest.timestamp,
            latest.importance,
            description.ifBlank { "-" },
        )
        breadcrumbStore.markExitReported(latest.timestamp)
    }

    companion object {
        private const val TAG = "ViwaDiag"
    }
}
