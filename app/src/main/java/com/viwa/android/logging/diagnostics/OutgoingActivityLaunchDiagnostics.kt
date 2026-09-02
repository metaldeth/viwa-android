package com.viwa.android.logging.diagnostics

import android.content.Intent
import timber.log.Timber

/**
 * Sanitized summary of an outgoing [Intent] for field diagnostics.
 * Never includes URI, extras, tokens, or credentials.
 */
data class SanitizedOutgoingLaunch(
    val action: String?,
    val mimeType: String?,
    val componentPackage: String?,
    val componentClass: String?,
    val flagsHex: String,
    val caller: String,
) {
    fun toLogLine(): String =
        buildString {
            append("activity.launch caller=")
            append(caller)
            append(" action=")
            append(action ?: "-")
            append(" mime=")
            append(mimeType ?: "-")
            append(" pkg=")
            append(componentPackage ?: "-")
            append(" cls=")
            append(componentClass ?: "-")
            append(" flags=0x")
            append(flagsHex)
        }

    fun toBreadcrumbSummary(): String =
        "$caller|${action ?: "-"}|${componentClass ?: componentPackage ?: "-"}|0x$flagsHex"
}

object OutgoingActivityLaunchDiagnostics {
    fun sanitize(
        intent: Intent,
        caller: String,
    ): SanitizedOutgoingLaunch {
        val component = intent.component
        return SanitizedOutgoingLaunch(
            action = intent.action?.take(ACTION_LIMIT),
            mimeType = intent.type?.take(MIME_LIMIT),
            componentPackage = component?.packageName?.take(PACKAGE_LIMIT),
            componentClass = component?.className?.take(CLASS_LIMIT),
            flagsHex = intent.flags.toUInt().toString(16),
            caller = caller.take(CALLER_LIMIT),
        )
    }

    fun logBeforeLaunch(
        caller: String,
        intent: Intent,
    ) {
        val sanitized = sanitize(intent, caller)
        Timber.tag(TAG).i(sanitized.toLogLine())
    }

    fun logBeforeLaunch(
        caller: String,
        intent: Intent,
        breadcrumbStore: DiagnosticBreadcrumbStore,
    ) {
        val sanitized = sanitize(intent, caller)
        Timber.tag(TAG).i(sanitized.toLogLine())
        breadcrumbStore.recordOutgoingLaunch(sanitized.toBreadcrumbSummary())
    }

    private const val TAG = "ViwaDiag"
    private const val ACTION_LIMIT = 120
    private const val MIME_LIMIT = 80
    private const val PACKAGE_LIMIT = 120
    private const val CLASS_LIMIT = 160
    private const val CALLER_LIMIT = 80
}
