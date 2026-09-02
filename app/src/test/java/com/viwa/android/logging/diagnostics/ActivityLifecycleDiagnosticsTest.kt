package com.viwa.android.logging.diagnostics

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class ActivityLifecycleDiagnosticsTest {
    private lateinit var store: DiagnosticBreadcrumbStore
    private lateinit var diagnostics: ActivityLifecycleDiagnostics

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store =
            DiagnosticBreadcrumbStore(
                context = context,
                ioScope = CoroutineScope(SupervisorJob()),
            )
        store.startBootSession("boot-test")
        diagnostics = ActivityLifecycleDiagnostics(store)
    }

    @Test
    fun windowFocus_logsOnlyOnTransition() {
        val controller: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.setup().get()

        diagnostics.logWindowFocusChanged(activity, hasFocus = true)
        diagnostics.logWindowFocusChanged(activity, hasFocus = true)

        val snapshot = store.readPreviousSnapshot()
        assertNotNull(snapshot)
        assertEquals("windowFocusGained", snapshot!!.activityLifecycleEvent)
    }

    @Test
    fun foregroundTransition_updatesBreadcrumb() {
        diagnostics.logForegroundTransition(
            count = 1,
            event = ActivityLifecycleDiagnostics.ForegroundTransitionEvent.Started,
            activityClass = "MainActivity",
        )

        val snapshot = store.readPreviousSnapshot()
        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.activityForegroundCount)
        assertEquals("foreground.started", snapshot.activityLifecycleEvent)
        assertEquals("MainActivity", snapshot.activityClass)
    }
}
