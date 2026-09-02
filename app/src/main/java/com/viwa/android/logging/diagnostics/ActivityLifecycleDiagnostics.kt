package com.viwa.android.logging.diagnostics

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class ActivityLifecycleDiagnostics
@Inject
constructor(
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
) {
    @Volatile
    private var lastLoggedWindowFocus: Boolean? = null

    fun logLifecycle(
        activity: Activity,
        event: String,
    ) {
        val bootSessionId = breadcrumbStore.currentBootSessionId()
        Timber.tag(TAG).i(
            "activity.lifecycle event=%s hasWindowFocus=%s isFinishing=%s isChangingConfigurations=%s bootSessionId=%s",
            event,
            activity.hasWindowFocus(),
            activity.isFinishing,
            activity.isChangingConfigurations,
            bootSessionId.ifBlank { "-" },
        )
        breadcrumbStore.recordActivityLifecycle(
            event = event,
            hasWindowFocus = activity.hasWindowFocus(),
            isFinishing = activity.isFinishing,
            isChangingConfigurations = activity.isChangingConfigurations,
            activityClass = activity.javaClass.simpleName,
        )
    }

    fun logUserLeaveHint(activity: Activity) {
        val bootSessionId = breadcrumbStore.currentBootSessionId()
        Timber.tag(TAG).i(
            "activity.userLeaveHint hasWindowFocus=%s isFinishing=%s bootSessionId=%s",
            activity.hasWindowFocus(),
            activity.isFinishing,
            bootSessionId.ifBlank { "-" },
        )
        breadcrumbStore.recordActivityLifecycle(
            event = "userLeaveHint",
            hasWindowFocus = activity.hasWindowFocus(),
            isFinishing = activity.isFinishing,
            isChangingConfigurations = activity.isChangingConfigurations,
            activityClass = activity.javaClass.simpleName,
        )
    }

    fun logWindowFocusChanged(
        activity: Activity,
        hasFocus: Boolean,
    ) {
        if (lastLoggedWindowFocus == hasFocus) return
        lastLoggedWindowFocus = hasFocus
        val bootSessionId = breadcrumbStore.currentBootSessionId()
        Timber.tag(TAG).i(
            "activity.windowFocus hasFocus=%s isFinishing=%s bootSessionId=%s",
            hasFocus,
            activity.isFinishing,
            bootSessionId.ifBlank { "-" },
        )
        breadcrumbStore.recordActivityLifecycle(
            event = if (hasFocus) "windowFocusGained" else "windowFocusLost",
            hasWindowFocus = hasFocus,
            isFinishing = activity.isFinishing,
            isChangingConfigurations = activity.isChangingConfigurations,
            activityClass = activity.javaClass.simpleName,
        )
    }

    fun logForegroundTransition(
        count: Int,
        event: ForegroundTransitionEvent,
        activityClass: String,
    ) {
        Timber.tag(TAG).i(
            "activity.foreground count=%d event=%s class=%s",
            count,
            event.wireName,
            activityClass,
        )
        breadcrumbStore.recordForegroundTransition(
            count = count,
            event = event.wireName,
            activityClass = activityClass,
        )
    }

    internal fun resetWindowFocusStateForTests() {
        lastLoggedWindowFocus = null
    }

    enum class ForegroundTransitionEvent(val wireName: String) {
        Started("started"),
        Stopped("stopped"),
    }

    companion object {
        private const val TAG = "ViwaDiag"
    }
}
