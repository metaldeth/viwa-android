package com.viwa.android.logging.diagnostics

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PourDiagnosticsHelper
@Inject
constructor(
    private val breadcrumbStore: DiagnosticBreadcrumbStore,
) {
    @Volatile
    private var activePourId: String? = null

    fun beginPour(): String {
        val pourId = newPourId()
        activePourId = pourId
        logPhase("pour.begin", pourId)
        return pourId
    }

    fun commandDone(pourId: String) {
        logPhase("pour.command.done", pourId)
    }

    fun commandFailed(
        pourId: String,
        errorType: String,
    ) {
        Timber.tag(TAG).w("pour.command.failed pourId=%s errorType=%s", pourId, errorType)
        breadcrumbStore.recordPour(pourId, "command.failed:$errorType")
    }

    fun telemetryBegin(pourId: String) {
        logPhase("pour.telemetry.begin", pourId)
    }

    fun maxHold(pourId: String) {
        logPhase("pour.max_hold", pourId)
    }

    fun end(
        pourId: String,
        reason: String,
    ) {
        if (activePourId != pourId) return
        Timber.tag(TAG).i("pour.end pourId=%s reason=%s", pourId, reason)
        breadcrumbStore.recordPour(pourId, "end:$reason")
        activePourId = null
    }

    fun activePourIdOrNull(): String? = activePourId

    fun clearActive() {
        activePourId = null
    }

    private fun logPhase(
        phase: String,
        pourId: String,
    ) {
        Timber.tag(TAG).i("%s pourId=%s", phase, pourId)
        breadcrumbStore.recordPour(pourId, phase.removePrefix("pour."))
    }

    private fun newPourId(): String = UUID.randomUUID().toString().substring(0, 8)

    companion object {
        private const val TAG = "ViwaDiag"
    }
}
