package com.viwa.android.logging.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticBreadcrumbSnapshot(
    val formatVersion: Int = FORMAT_VERSION,
    val bootSessionId: String = "",
    val updatedAt: String = "",
    val uptimeMs: Long = 0L,
    val screenRoute: String = "?",
    val screenSnapshot: String = "",
    val pourId: String? = null,
    val pourPhase: String? = null,
    val lastControllerOpId: Long? = null,
    val lastControllerOpPhase: String? = null,
    val lastControllerCmd: String? = null,
    val lastControllerWriteMs: Long? = null,
    val mainThreadHeartbeatMs: Long? = null,
    val mainThreadStalled: Boolean = false,
    val trail: List<String> = emptyList(),
    val lastCrashType: String? = null,
    val lastCrashMessage: String? = null,
    val lastReportedExitTimestamp: Long? = null,
    val activityLifecycleEvent: String? = null,
    val activityClass: String? = null,
    val activityHasWindowFocus: Boolean? = null,
    val activityIsFinishing: Boolean? = null,
    val activityForegroundCount: Int? = null,
    val lastOutgoingLaunch: String? = null,
    val lastForegroundPackage: String? = null,
    val lastForegroundClass: String? = null,
    val lastForegroundObservedAt: Long? = null,
    val foregroundProbeStatus: String? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val TRAIL_LIMIT = 24
    }
}
