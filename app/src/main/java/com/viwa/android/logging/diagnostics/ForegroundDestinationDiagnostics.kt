package com.viwa.android.logging.diagnostics

import com.viwa.android.di.AppIoScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class ForegroundDestinationDiagnostics private constructor(
    private val usageStatsProvider: UsageStatsForegroundProvider,
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
    private val foregroundState: ViwaForegroundState,
    private val ioScope: CoroutineScope,
    private val probeConfig: ForegroundProbeConfig,
) {
    @Inject
    constructor(
        usageStatsProvider: UsageStatsForegroundProvider,
        breadcrumbStore: DiagnosticBreadcrumbStore,
        foregroundState: ViwaForegroundState,
        @AppIoScope ioScope: CoroutineScope,
    ) : this(
        usageStatsProvider = usageStatsProvider,
        breadcrumbStore = breadcrumbStore,
        foregroundState = foregroundState,
        ioScope = ioScope,
        probeConfig = ForegroundProbeConfig.DEFAULT,
    )
    @Volatile
    private var lastLoggedDestinationKey: String? = null

    @Volatile
    private var probeUnavailableLoggedThisBoot: Boolean = false

    private val pendingProbeJobs = mutableListOf<Job>()

    fun resetForBootSession() {
        lastLoggedDestinationKey = null
        probeUnavailableLoggedThisBoot = false
        cancelPendingProbes()
    }

    fun onViwaForegrounded() {
        cancelPendingProbes()
    }

    fun onViwaBackgrounded(fromComponent: String) {
        if (breadcrumbStore.configChangeInProgress) return
        cancelPendingProbes()
        probeConfig.probeDelayMs.forEach { delayMs ->
            val job =
                ioScope.launch {
                    delay(delayMs)
                    if (foregroundState.isInForeground()) return@launch
                    runTransitionProbe(fromComponent)
                }
            synchronized(pendingProbeJobs) {
                pendingProbeJobs += job
                job.invokeOnCompletion { synchronized(pendingProbeJobs) { pendingProbeJobs.remove(job) } }
            }
        }
    }

    fun reportStartupPreviousExternal(previousUpdatedAtIso: String?) {
        val sinceMs = parseUpdatedAtMs(previousUpdatedAtIso)
        when (val status = usageStatsProvider.usageAccessStatus()) {
            UsageAccessStatus.GRANTED -> Unit
            UsageAccessStatus.DENIED -> {
                logProbeUnavailableOnce("usage_access_denied")
                return
            }
            UsageAccessStatus.QUERY_ERROR -> {
                logProbeUnavailableOnce("query_error")
                return
            }
        }
        val observation =
            usageStatsProvider.latestForegroundEvent(
                windowMs = probeConfig.startupHistoryWindowMs,
                sinceMs = sinceMs,
                externalOnly = true,
            ) ?: return
        Timber.tag(TAG).i(
            "foreground.previous_external pkg=%s class=%s observedAt=%d source=usage_events",
            observation.packageName,
            observation.className,
            observation.observedAtMs,
        )
        breadcrumbStore.recordForegroundDestination(
            packageName = observation.packageName,
            className = observation.className,
            observedAtMs = observation.observedAtMs,
            probeStatus = "previous_external",
        )
    }

    internal suspend fun runTransitionProbe(fromComponent: String) {
        when (val status = usageStatsProvider.usageAccessStatus()) {
            UsageAccessStatus.GRANTED -> Unit
            UsageAccessStatus.DENIED -> {
                logProbeUnavailableOnce("usage_access_denied")
                return
            }
            UsageAccessStatus.QUERY_ERROR -> {
                logProbeUnavailableOnce("query_error")
                return
            }
        }
        val observation =
            usageStatsProvider.latestForegroundEvent(
                windowMs = probeConfig.transitionWindowMs,
                sinceMs = 0L,
                externalOnly = true,
            ) ?: return
        if (UsageStatsForegroundEventParser.isOwnPackage(observation.packageName, VIWA_PACKAGE)) {
            return
        }
        val destinationKey = observation.componentKey()
        if (destinationKey == lastLoggedDestinationKey) return
        lastLoggedDestinationKey = destinationKey
        Timber.tag(TAG).i(
            "foreground.changed from=%s to=%s observedAt=%d source=usage_events",
            fromComponent,
            observation.toLogComponent(),
            observation.observedAtMs,
        )
        breadcrumbStore.recordForegroundDestination(
            packageName = observation.packageName,
            className = observation.className,
            observedAtMs = observation.observedAtMs,
            probeStatus = "changed",
        )
    }

    internal fun logProbeUnavailableOnce(reason: String) {
        if (probeUnavailableLoggedThisBoot) return
        probeUnavailableLoggedThisBoot = true
        Timber.tag(TAG).w("foreground.probe_unavailable reason=%s", reason)
        breadcrumbStore.recordForegroundProbeStatus("unavailable:$reason")
    }

    internal fun setLastLoggedDestinationForTests(key: String?) {
        lastLoggedDestinationKey = key
    }

    internal fun isProbeUnavailableLoggedForTests(): Boolean = probeUnavailableLoggedThisBoot

    internal fun pendingProbeCountForTests(): Int = synchronized(pendingProbeJobs) { pendingProbeJobs.count { it.isActive } }

    private fun cancelPendingProbes() {
        synchronized(pendingProbeJobs) {
            pendingProbeJobs.forEach { it.cancel() }
            pendingProbeJobs.clear()
        }
    }

    private fun parseUpdatedAtMs(iso: String?): Long =
        runCatching {
            if (iso.isNullOrBlank()) return 0L
            Instant.parse(iso).toEpochMilli()
        }.getOrDefault(0L)

    companion object {
        private const val TAG = "ViwaDiag"
        private const val VIWA_PACKAGE = "com.viwa.android"

        internal fun forTests(
            usageStatsProvider: UsageStatsForegroundProvider,
            breadcrumbStore: DiagnosticBreadcrumbStore,
            foregroundState: ViwaForegroundState,
            ioScope: CoroutineScope,
            probeConfig: ForegroundProbeConfig,
        ): ForegroundDestinationDiagnostics =
            ForegroundDestinationDiagnostics(
                usageStatsProvider = usageStatsProvider,
                breadcrumbStore = breadcrumbStore,
                foregroundState = foregroundState,
                ioScope = ioScope,
                probeConfig = probeConfig,
            )
    }
}

data class ForegroundProbeConfig(
    val probeDelayMs: List<Long>,
    val transitionWindowMs: Long,
    val startupHistoryWindowMs: Long,
) {
    companion object {
        val DEFAULT =
            ForegroundProbeConfig(
                probeDelayMs = listOf(1_000L, 5_000L),
                transitionWindowMs = 15_000L,
                startupHistoryWindowMs = 15 * 60 * 1000L,
            )
    }
}
