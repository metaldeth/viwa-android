package com.viwa.android.ui.screens.idle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class IdleVideoPhaseScheduler(
    private val scope: CoroutineScope,
    private val isScreenActive: () -> Boolean,
    private val enabledVideoIds: () -> List<String>,
    private val onPhaseChanged: (IdlePhase) -> Unit,
) {
    private var idleJob: Job? = null
    private var prewarmReadySignal: CompletableDeferred<Unit>? = null

    fun scheduleIdle() {
        idleJob?.cancel()
        prewarmReadySignal?.cancel()
        prewarmReadySignal = null
        onPhaseChanged(IdlePhase.Hidden)
        if (!isScreenActive() || enabledVideoIds().isEmpty()) return
        idleJob =
            scope.launch {
                while (isScreenActive() && enabledVideoIds().isNotEmpty()) {
                    delay(IdleVideoViewModel.IDLE_TIMEOUT_MS - IdleVideoViewModel.IDLE_PREWARM_LEAD_MS)
                    if (!isScreenActive() || enabledVideoIds().isEmpty()) break

                    onPhaseChanged(IdlePhase.Prewarm)
                    val signal = CompletableDeferred<Unit>()
                    prewarmReadySignal = signal

                    val ready =
                        withTimeoutOrNull(IdleVideoViewModel.PREWARM_READY_TIMEOUT_MS) {
                            signal.await()
                        } != null

                    prewarmReadySignal = null

                    // cancel() не прерывает синхронный хвост корутины: без isActive
                    // касание, пришедшее ровно здесь, не помешает показу оверлея.
                    if (!isActive || !isScreenActive() || enabledVideoIds().isEmpty()) {
                        onPhaseChanged(IdlePhase.Hidden)
                        break
                    }

                    if (ready) {
                        onPhaseChanged(IdlePhase.Visible)
                        return@launch
                    }

                    onPhaseChanged(IdlePhase.Hidden)
                }
            }
    }

    fun onPrewarmReady() {
        prewarmReadySignal?.complete(Unit)
    }

    fun cancelAndHide() {
        idleJob?.cancel()
        prewarmReadySignal?.cancel()
        prewarmReadySignal = null
        onPhaseChanged(IdlePhase.Hidden)
    }

    fun clear() {
        idleJob?.cancel()
        prewarmReadySignal?.cancel()
        prewarmReadySignal = null
    }
}
