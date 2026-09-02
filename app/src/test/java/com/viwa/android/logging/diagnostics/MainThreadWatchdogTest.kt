package com.viwa.android.logging.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeWatchdogUsageStatsProvider : UsageStatsForegroundProvider {
    override fun usageAccessStatus(): UsageAccessStatus = UsageAccessStatus.GRANTED

    override fun latestForegroundEvent(
        windowMs: Long,
        sinceMs: Long,
        externalOnly: Boolean,
    ): ForegroundUsageObservation? = null
}

@RunWith(RobolectricTestRunner::class)
class MainThreadWatchdogTest {
    private lateinit var breadcrumbStore: DiagnosticBreadcrumbStore
    private lateinit var watchdog: MainThreadWatchdog

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        breadcrumbStore =
            DiagnosticBreadcrumbStore(
                context = context,
                ioScope = CoroutineScope(SupervisorJob()),
            )
        val foregroundState = ViwaForegroundState()
        watchdog =
            MainThreadWatchdog(
                CoroutineScope(SupervisorJob()),
                breadcrumbStore,
                ActivityLifecycleDiagnostics(breadcrumbStore),
                foregroundState,
                ForegroundDestinationDiagnostics.forTests(
                    FakeWatchdogUsageStatsProvider(),
                    breadcrumbStore,
                    foregroundState,
                    CoroutineScope(SupervisorJob()),
                    ForegroundProbeConfig.DEFAULT,
                ),
            )
    }

    @Test
    fun noWarningWhileBackground() {
        watchdog.setForegroundForTests(0)
        watchdog.evaluateStall(MainThreadWatchdog.STALL_THRESHOLD_MS + 1_000L)
        assertFalse(watchdog.isStallWarnedForTests())
    }

    @Test
    fun oneStallThenRecovery() {
        watchdog.setForegroundForTests(1)
        watchdog.evaluateStall(MainThreadWatchdog.STALL_THRESHOLD_MS + 500L)
        assertTrue(watchdog.isStallWarnedForTests())

        watchdog.simulateHeartbeat()
        watchdog.evaluateStall(MainThreadWatchdog.RECOVERY_THRESHOLD_MS - 1_000L)
        assertFalse(watchdog.isStallWarnedForTests())
    }

    @Test
    fun backgroundDuringStall_recordsTransition() {
        watchdog.setForegroundForTests(1)
        watchdog.evaluateStall(MainThreadWatchdog.STALL_THRESHOLD_MS + 500L)
        assertTrue(watchdog.isStallWarnedForTests())

        watchdog.setForegroundForTests(0)
        breadcrumbStore.recordWatchdogBackgroundDuringStall(System.currentTimeMillis())
        watchdog.setStallWarnedForTests(false)

        val snapshot = breadcrumbStore.readPreviousSnapshot()
        assertTrue(snapshot?.trail?.any { it.contains("backgroundDuringStall") } == true)
    }
}
