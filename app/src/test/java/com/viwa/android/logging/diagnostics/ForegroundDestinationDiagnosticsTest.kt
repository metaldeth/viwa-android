package com.viwa.android.logging.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundDestinationDiagnosticsTest {
    private class RecordingTree : Timber.Tree() {
        val messages = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "ViwaDiag") messages += message
        }
    }

    private class FakeUsageStatsForegroundProvider : UsageStatsForegroundProvider {
        var accessStatus: UsageAccessStatus = UsageAccessStatus.GRANTED
        var latestEvent: ForegroundUsageObservation? = null
        var queryCount: Int = 0

        override fun usageAccessStatus(): UsageAccessStatus = accessStatus

        override fun latestForegroundEvent(
            windowMs: Long,
            sinceMs: Long,
            externalOnly: Boolean,
        ): ForegroundUsageObservation? {
            queryCount++
            return latestEvent
        }
    }

    private lateinit var context: Context
    private lateinit var store: DiagnosticBreadcrumbStore
    private lateinit var foregroundState: ViwaForegroundState
    private lateinit var provider: FakeUsageStatsForegroundProvider
    private lateinit var diagnostics: ForegroundDestinationDiagnostics
    private lateinit var tree: RecordingTree
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store =
            DiagnosticBreadcrumbStore(
                context = context,
                ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            )
        foregroundState = ViwaForegroundState()
        provider = FakeUsageStatsForegroundProvider()
        diagnostics =
            ForegroundDestinationDiagnostics.forTests(
                usageStatsProvider = provider,
                breadcrumbStore = store,
                foregroundState = foregroundState,
                ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                probeConfig =
                    ForegroundProbeConfig(
                        probeDelayMs = listOf(1_000L, 5_000L),
                        transitionWindowMs = 15_000L,
                        startupHistoryWindowMs = 15 * 60 * 1000L,
                    ),
            )
        tree = RecordingTree()
        Timber.plant(tree)
        diagnostics.resetForBootSession()
    }

    @org.junit.After
    fun tearDown() {
        Timber.uproot(tree)
    }

    @Test
    fun transitionProbe_logsSettingsDestination() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.android.settings",
                    className = "com.android.settings.homepage.SettingsHomepageActivity",
                    observedAtMs = 8_000L,
                )
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(1_100L)

            assertTrue(
                tree.messages.any {
                    it.contains("to=com.android.settings/com.android.settings.homepage.SettingsHomepageActivity")
                },
            )
        }

    @Test
    fun transitionProbe_logsAndPersistsExternalDestination() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    observedAtMs = 9_000L,
                )
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(1_100L)

            assertTrue(tree.messages.any { it.startsWith("foreground.changed from=") })
            val snapshot = store.readPreviousSnapshot()
            assertEquals("com.oem.video", snapshot!!.lastForegroundPackage)
            assertEquals("com.oem.video.ListActivity", snapshot.lastForegroundClass)
            assertEquals(9_000L, snapshot.lastForegroundObservedAt)
            assertEquals("changed", snapshot.foregroundProbeStatus)
        }

    @Test
    fun transitionProbe_dedupesSameDestination() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    observedAtMs = 9_000L,
                )
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(1_100L)
            val firstCount = tree.messages.count { it.startsWith("foreground.changed") }

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(5_100L)
            val secondCount = tree.messages.count { it.startsWith("foreground.changed") }

            assertEquals(1, firstCount)
            assertEquals(1, secondCount)
        }

    @Test
    fun transitionProbe_ignoresOwnPackageObservation() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.viwa.android",
                    className = "com.viwa.android.ui.MainActivity",
                    observedAtMs = 9_000L,
                )
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(5_100L)

            assertFalse(tree.messages.any { it.startsWith("foreground.changed") })
        }

    @Test
    fun permissionDenied_logsOncePerBoot() =
        runTest(testDispatcher) {
            provider.accessStatus = UsageAccessStatus.DENIED
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(5_100L)
            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(5_100L)

            val unavailable =
                tree.messages.filter { it.startsWith("foreground.probe_unavailable reason=usage_access_denied") }
            assertEquals(1, unavailable.size)
            assertTrue(diagnostics.isProbeUnavailableLoggedForTests())
        }

    @Test
    fun startupPreviousExternal_logsSettingsAfterOwnPackageFiltered() {
        provider.latestEvent =
            ForegroundUsageObservation(
                packageName = "com.android.documentsui",
                className = "com.android.documentsui.files.FilesActivity",
                observedAtMs = 42_000L,
            )

        diagnostics.reportStartupPreviousExternal("1970-01-01T00:00:00Z")

        assertTrue(tree.messages.any { it.contains("pkg=com.android.documentsui") })
        val snapshot = store.readPreviousSnapshot()
        assertEquals("com.android.documentsui", snapshot!!.lastForegroundPackage)
    }

    @Test
    fun startupPreviousExternal_logsLatestExternalEvent() {
        provider.latestEvent =
            ForegroundUsageObservation(
                packageName = "com.oem.video",
                className = "com.oem.video.ListActivity",
                observedAtMs = 42_000L,
            )

        diagnostics.reportStartupPreviousExternal("1970-01-01T00:00:00Z")

        assertTrue(tree.messages.any { it.startsWith("foreground.previous_external") })
        val snapshot = store.readPreviousSnapshot()
        assertEquals("com.oem.video", snapshot!!.lastForegroundPackage)
        assertEquals("previous_external", snapshot.foregroundProbeStatus)
    }

    @Test
    fun startupPreviousExternal_whenDenied_logsUnavailableOnce() {
        provider.accessStatus = UsageAccessStatus.DENIED

        diagnostics.reportStartupPreviousExternal(null)
        diagnostics.reportStartupPreviousExternal(null)

        val unavailable =
            tree.messages.filter { it.startsWith("foreground.probe_unavailable reason=usage_access_denied") }
        assertEquals(1, unavailable.size)
    }

    @Test
    fun transitionProbe_skipsWhenViwaReturnsToForeground() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    observedAtMs = 9_000L,
                )

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            foregroundState.activityForegroundCount = 1
            diagnostics.onViwaForegrounded()
            advanceTimeBy(5_100L)

            assertFalse(tree.messages.any { it.startsWith("foreground.changed") })
            assertEquals(0, provider.queryCount)
        }

    @Test
    fun rapidBackgroundTransitions_cancelStaleProbes() =
        runTest(testDispatcher) {
            provider.latestEvent =
                ForegroundUsageObservation(
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    observedAtMs = 9_000L,
                )
            foregroundState.activityForegroundCount = 0

            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(500L)
            diagnostics.onViwaBackgrounded("com.viwa.android/.ui.MainActivity")
            advanceTimeBy(1_100L)

            assertEquals(1, provider.queryCount)
            assertTrue(tree.messages.any { it.startsWith("foreground.changed") })
        }
}
