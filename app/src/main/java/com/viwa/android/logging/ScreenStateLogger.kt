package com.viwa.android.logging

import timber.log.Timber

/**
 * Полевой журнал экрана: текущее положение, idle/оверлей и короткий хвост действий.
 * INFO/WARN всегда уходят в log-ship — не прятать за [BuildConfig.DEBUG].
 */
object ScreenStateLogger {
    const val TAG = "ScreenState"
    private const val TRAIL_SIZE = 16

    @Volatile
    var route: String = "?"

    @Volatile
    var idlePhase: String = "Hidden"

    @Volatile
    var idleAllowed: Boolean = false

    @Volatile
    var homeIdleBlocked: Boolean = false

    @Volatile
    var overlay: String = "none"

    @Volatile
    var preparing: String? = null

    private val lock = Any()
    private val trail = ArrayDeque<String>(TRAIL_SIZE)

    fun action(event: String) {
        val stamped = stamp(event)
        synchronized(lock) {
            if (trail.size >= TRAIL_SIZE) trail.removeFirst()
            trail.addLast(stamped)
        }
        Timber.tag(TAG).i("%s | %s | trail=[%s]", event, snapshot(), trailJoined())
    }

    fun black(reason: String, extra: String = "") {
        val detail = if (extra.isBlank()) reason else "$reason $extra"
        action("BLACK:$detail")
        Timber.tag(TAG).w("BLACK_SCREEN reason=%s %s | %s | trail=[%s]", reason, extra, snapshot(), trailJoined())
    }

    fun snapshot(): String =
        "route=$route idle=$idlePhase allowed=$idleAllowed blocked=$homeIdleBlocked " +
            "overlay=$overlay preparing=${preparing ?: "-"}"

    private fun trailJoined(): String = synchronized(lock) { trail.joinToString(" → ") }

    private fun stamp(event: String): String = "${System.currentTimeMillis() % 100_000}:$event"
}
