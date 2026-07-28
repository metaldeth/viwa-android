package com.viwa.android.ui.screens.idle

import androidx.media3.common.Player

/** Pure timing/state-machine for idle-video playback stall detection (unit-testable). */
internal object IdleVideoStallWatchdog {
    const val TICK_INTERVAL_MS = 5_000L
    const val STALL_THRESHOLD_MS = 15_000L
    private const val MIN_POSITION_ADVANCE_MS = 50L
    const val MAX_RECOVERY_ATTEMPTS_PER_ASSET = 3
    const val RECOVERY_BACKOFF_BASE_MS = 2_000L
    const val RECOVERY_BACKOFF_MAX_MS = 30_000L

    data class Snapshot(
        val positionMs: Long,
        val playbackState: Int,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
    )

    data class WatchContext(
        val lifecyclePaused: Boolean,
        val crossfading: Boolean,
        val nowMs: Long,
    )

    data class WatchState(
        val lastProgressPositionMs: Long? = null,
        val lastProgressAtMs: Long? = null,
        val recoveryAttempts: Int = 0,
        val backoffUntilMs: Long = 0L,
        val monitoringAssetIndex: Int = 0,
    )

    enum class RecoveryAction {
        None,
        SeekReprepare,
        RotateAsset,
    }

    data class TickResult(
        val state: WatchState,
        val action: RecoveryAction,
        val diagnostic: String? = null,
    )

    fun shouldMonitor(snapshot: Snapshot, context: WatchContext): Boolean {
        if (context.lifecyclePaused || context.crossfading) return false
        if (!snapshot.playWhenReady) return false
        if (snapshot.playbackState != Player.STATE_READY) return false
        return true
    }

    fun positionAdvanced(previousMs: Long, currentMs: Long): Boolean {
        if (currentMs > previousMs + MIN_POSITION_ADVANCE_MS) return true
        if (currentMs + MIN_POSITION_ADVANCE_MS < previousMs) return true
        return false
    }

    fun recoveryBackoffMs(attempt: Int): Long =
        (RECOVERY_BACKOFF_BASE_MS shl attempt.coerceAtMost(4)).coerceAtMost(RECOVERY_BACKOFF_MAX_MS)

    fun tick(
        state: WatchState,
        snapshot: Snapshot,
        context: WatchContext,
        activeAssetIndex: Int,
    ): TickResult {
        if (!shouldMonitor(snapshot, context)) {
            return TickResult(
                state = state.copy(
                    lastProgressPositionMs = null,
                    lastProgressAtMs = null,
                ),
                action = RecoveryAction.None,
            )
        }

        if (context.nowMs < state.backoffUntilMs) {
            return TickResult(state = state, action = RecoveryAction.None)
        }

        val lastPos = state.lastProgressPositionMs
        val lastAt = state.lastProgressAtMs

        if (lastPos == null || lastAt == null || state.monitoringAssetIndex != activeAssetIndex) {
            return TickResult(
                state = state.copy(
                    lastProgressPositionMs = snapshot.positionMs,
                    lastProgressAtMs = context.nowMs,
                    monitoringAssetIndex = activeAssetIndex,
                    recoveryAttempts = 0,
                ),
                action = RecoveryAction.None,
            )
        }

        if (positionAdvanced(lastPos, snapshot.positionMs)) {
            return TickResult(
                state = state.copy(
                    lastProgressPositionMs = snapshot.positionMs,
                    lastProgressAtMs = context.nowMs,
                    recoveryAttempts = 0,
                ),
                action = RecoveryAction.None,
            )
        }

        val stalledForMs = context.nowMs - lastAt
        if (stalledForMs < STALL_THRESHOLD_MS) {
            return TickResult(state = state, action = RecoveryAction.None)
        }

        val nextAttempt = state.recoveryAttempts + 1
        val backoffMs = recoveryBackoffMs(nextAttempt - 1)
        val nextState =
            state.copy(
                lastProgressPositionMs = snapshot.positionMs,
                lastProgressAtMs = context.nowMs,
                recoveryAttempts = nextAttempt,
                backoffUntilMs = context.nowMs + backoffMs,
            )

        val rotate = nextAttempt >= MAX_RECOVERY_ATTEMPTS_PER_ASSET
        val action = if (rotate) RecoveryAction.RotateAsset else RecoveryAction.SeekReprepare
        val diagnostic =
            buildDiagnostic(
                activeAssetIndex = activeAssetIndex,
                stalledForMs = stalledForMs,
                attempt = nextAttempt,
                rotate = rotate,
                snapshot = snapshot,
            )

        return TickResult(
            state =
                if (rotate) {
                    nextState.copy(recoveryAttempts = 0)
                } else {
                    nextState
                },
            action = action,
            diagnostic = diagnostic,
        )
    }

    private fun buildDiagnostic(
        activeAssetIndex: Int,
        stalledForMs: Long,
        attempt: Int,
        rotate: Boolean,
        snapshot: Snapshot,
    ): String =
        buildString {
            append("idle video stall: assetIndex=")
            append(activeAssetIndex)
            append(" stalledMs=")
            append(stalledForMs)
            append(" attempt=")
            append(attempt)
            append("/")
            append(MAX_RECOVERY_ATTEMPTS_PER_ASSET)
            append(" state=READY playing=")
            append(snapshot.isPlaying)
            append(" posMs=")
            append(snapshot.positionMs)
            if (rotate) append(" action=rotate")
            else append(" action=seekReprepare")
        }
}
