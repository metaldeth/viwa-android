package com.viwa.android.data.remote.telemetry.mvp

/**
 * Explicit connection phases for MVP telemetry WS (Phase 1 resilience).
 * Maps to UI [com.viwa.android.data.remote.telemetry.ConnectionState] in the manager.
 */
enum class TelemetryConnectionPhase {
    Idle,
    AwaitingNetwork,
    Connecting,
    AwaitingHello,
    Active,
    Backoff,
    AuthError,
    Superseded,
}

data class FsmTransition(
    val from: TelemetryConnectionPhase,
    val to: TelemetryConnectionPhase,
    val sessionGeneration: Long,
    val reason: String,
    val atMs: Long,
)

/**
 * Session generation monotonically increases on each connect attempt and on 4001 supersede.
 * Inbound callbacks and liveness jobs must match [sessionGeneration] and the active client.
 */
class TelemetryConnectionFsm(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    var phase: TelemetryConnectionPhase = TelemetryConnectionPhase.Idle
        private set

    var sessionGeneration: Long = 0
        private set

    var backoffAttempt: Int = 0
        private set

    private val _transitions = mutableListOf<FsmTransition>()

    val transitions: List<FsmTransition> get() = _transitions

    fun nextSessionGeneration(): Long {
        sessionGeneration += 1
        return sessionGeneration
    }

    fun bumpGenerationForSupersede(reason: String = "4001 superseded"): Long {
        sessionGeneration += 1
        transition(TelemetryConnectionPhase.Superseded, reason)
        return sessionGeneration
    }

    fun transition(
        to: TelemetryConnectionPhase,
        reason: String,
    ): FsmTransition? {
        val from = phase
        if (from == to) return null
        val transition =
            FsmTransition(
                from = from,
                to = to,
                sessionGeneration = sessionGeneration,
                reason = reason,
                atMs = clockMs(),
            )
        _transitions.add(transition)
        phase = to
        return transition
    }

    fun resetBackoff() {
        backoffAttempt = 0
    }

    fun incrementBackoff() {
        backoffAttempt += 1
    }

    fun resetToIdle() {
        phase = TelemetryConnectionPhase.Idle
        backoffAttempt = 0
    }

    internal fun assignSessionGenerationForTests(value: Long) {
        sessionGeneration = value
    }

    fun formatTransitionLog(transition: FsmTransition): String =
        "MVP WS FSM: ${transition.from} → ${transition.to} " +
            "gen=${transition.sessionGeneration} reason=${transition.reason}"
}
