package com.viwa.android.logging.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.viwa.android.di.AppIoScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground-only main-thread liveness probe. Diagnostics only — no recovery actions.
 */
@Singleton
class MainThreadWatchdog
@Inject
constructor(
    @AppIoScope private val appScope: CoroutineScope,
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
    private val activityLifecycleDiagnostics: ActivityLifecycleDiagnostics,
    private val foregroundState: ViwaForegroundState,
    private val foregroundDestinationDiagnostics: ForegroundDestinationDiagnostics,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable =
        Runnable {
            lastHeartbeatMs = SystemClock.elapsedRealtime()
            breadcrumbStore.recordHeartbeatRoutine(lastHeartbeatMs, stalled = stallWarned)
        }

    @Volatile
    private var started = false

    @Volatile
    private var foregroundActivityCount = 0

    @Volatile
    private var lastHeartbeatMs = SystemClock.elapsedRealtime()

    @Volatile
    private var stallWarned = false

    private var loopJob: Job? = null

    fun start(application: Application) {
        if (started) return
        started = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: android.os.Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) {
                    if (activity.isChangingConfigurations) return
                    val wasBackground = foregroundActivityCount == 0
                    foregroundActivityCount++
                    foregroundState.activityForegroundCount = foregroundActivityCount
                    if (wasBackground) {
                        foregroundDestinationDiagnostics.onViwaForegrounded()
                    }
                    activityLifecycleDiagnostics.logForegroundTransition(
                        count = foregroundActivityCount,
                        event = ActivityLifecycleDiagnostics.ForegroundTransitionEvent.Started,
                        activityClass = activity.javaClass.simpleName,
                    )
                }

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    if (activity.isChangingConfigurations) return
                    foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                    foregroundState.activityForegroundCount = foregroundActivityCount
                    activityLifecycleDiagnostics.logForegroundTransition(
                        count = foregroundActivityCount,
                        event = ActivityLifecycleDiagnostics.ForegroundTransitionEvent.Stopped,
                        activityClass = activity.javaClass.simpleName,
                    )
                    if (foregroundActivityCount == 0) {
                        foregroundDestinationDiagnostics.onViwaBackgrounded(
                            diagnosticComponentName(activity),
                        )
                    }
                    if (foregroundActivityCount == 0 && stallWarned) {
                        Timber.tag(TAG).i("main.watchdog.backgroundDuringStall")
                        breadcrumbStore.recordWatchdogBackgroundDuringStall(lastHeartbeatMs)
                        stallWarned = false
                    }
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: android.os.Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        loopJob =
            appScope.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    if (!isForeground()) continue
                    if (breadcrumbStore.configChangeInProgress) continue
                    postHeartbeatPing()
                    val ageMs = SystemClock.elapsedRealtime() - lastHeartbeatMs
                    evaluateStall(ageMs)
                }
            }
    }

    internal fun isForeground(): Boolean = foregroundActivityCount > 0

    internal fun evaluateStall(ageMs: Long) {
        if (!isForeground()) return
        if (breadcrumbStore.configChangeInProgress) return
        if (ageMs >= STALL_THRESHOLD_MS && !stallWarned) {
            stallWarned = true
            Timber.tag(TAG).w("main.watchdog.stall ageMs=%d", ageMs)
            breadcrumbStore.recordWatchdogStall(lastHeartbeatMs, ageMs)
        } else if (ageMs < RECOVERY_THRESHOLD_MS && stallWarned) {
            stallWarned = false
            Timber.tag(TAG).i("main.watchdog.recovered ageMs=%d", ageMs)
            breadcrumbStore.recordWatchdogRecovered(lastHeartbeatMs, ageMs)
        }
    }

    internal fun simulateHeartbeat() {
        lastHeartbeatMs = SystemClock.elapsedRealtime()
    }

    internal fun setForegroundForTests(count: Int) {
        foregroundActivityCount = count
        foregroundState.activityForegroundCount = count
    }

    private fun diagnosticComponentName(activity: Activity): String {
        val pkg = activity.packageName
        val relative = activity.javaClass.name.removePrefix("$pkg.")
        return "$pkg/.$relative"
    }

    internal fun isStallWarnedForTests(): Boolean = stallWarned

    internal fun setStallWarnedForTests(value: Boolean) {
        stallWarned = value
    }

    private fun postHeartbeatPing() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.post(heartbeatRunnable)
    }

    companion object {
        private const val TAG = "ViwaDiag"
        internal const val PING_INTERVAL_MS = 5_000L
        internal const val STALL_THRESHOLD_MS = 12_000L
        internal const val RECOVERY_THRESHOLD_MS = 8_000L
    }
}
