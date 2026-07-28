package com.viwa.android.data.remote.telemetry.mvp

/**
 * Guards WS callbacks so only the active client + session generation can mutate connection state.
 */
internal object WsSessionFence {
    fun accept(
        sourceClient: Any?,
        sourceGeneration: Long,
        activeClient: Any?,
        activeGeneration: Long,
    ): Boolean = sourceClient === activeClient && sourceGeneration == activeGeneration

    fun dropReason(
        event: String,
        sourceGeneration: Long,
        activeGeneration: Long,
        sameClient: Boolean,
    ): String =
        "MVP WS fence: drop stale $event gen=$sourceGeneration active=$activeGeneration sameClient=$sameClient"
}
