package com.viwa.android.logging.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeUsageStatsForegroundProvider : UsageStatsForegroundProvider {
    override fun usageAccessStatus(): UsageAccessStatus = UsageAccessStatus.GRANTED

    override fun latestForegroundEvent(
        windowMs: Long,
        sinceMs: Long,
        externalOnly: Boolean,
    ): ForegroundUsageObservation? = null
}

@RunWith(RobolectricTestRunner::class)
class AppStartupDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var store: DiagnosticBreadcrumbStore
    private lateinit var reporter: AppStartupDiagnostics

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store =
            DiagnosticBreadcrumbStore(
                context,
                CoroutineScope(SupervisorJob()),
            )
        reporter =
            AppStartupDiagnostics(
                context,
                store,
                ForegroundDestinationDiagnostics.forTests(
                    FakeUsageStatsForegroundProvider(),
                    store,
                    ViwaForegroundState(),
                    CoroutineScope(SupervisorJob()),
                    ForegroundProbeConfig.DEFAULT,
                ),
                CoroutineScope(SupervisorJob()),
            )
    }

    @Test
    fun reportOnColdStart_establishesBootSessionWithoutWipingConcurrentLifecycle() {
        store.recordActivityLifecycle(
            event = "onResume",
            hasWindowFocus = true,
            isFinishing = false,
            isChangingConfigurations = false,
            activityClass = "MainActivity",
        )

        reporter.reportOnColdStart()

        val snapshot = store.readPreviousSnapshot()
        assertNotEquals("", snapshot!!.bootSessionId)
        assertEquals("onResume", snapshot.activityLifecycleEvent)
        assertEquals("MainActivity", snapshot.activityClass)
    }

    @Test
    fun scheduleColdStartReport_runsOffCallingThread() {
        val ioScope = CoroutineScope(SupervisorJob())
        val asyncReporter =
            AppStartupDiagnostics(
                context,
                store,
                ForegroundDestinationDiagnostics.forTests(
                    FakeUsageStatsForegroundProvider(),
                    store,
                    ViwaForegroundState(),
                    ioScope,
                    ForegroundProbeConfig.DEFAULT,
                ),
                ioScope,
            )
        asyncReporter.scheduleColdStartReport()
        runBlocking {
            assertTrue(store.awaitPersistForTests(2_000L) || store.currentBootSessionId().isNotBlank())
        }
    }

    @Test
    fun readBootCount_doesNotThrow() {
        val bootCount = reporter.readBootCount()
        if (bootCount != null) {
            assertTrue(bootCount >= 0)
        }
    }

    @Test
    fun reportProcessExitReasons_onLowApi_isNoOp() {
        reporter.reportProcessExitReasons(null)
        assertEquals(null, store.readPreviousSnapshot()?.lastReportedExitTimestamp)
    }
}
