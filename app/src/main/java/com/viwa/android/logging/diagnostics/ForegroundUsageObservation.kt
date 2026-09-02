package com.viwa.android.logging.diagnostics

data class ForegroundUsageObservation(
    val packageName: String,
    val className: String,
    val observedAtMs: Long,
) {
    fun componentKey(): String = "$packageName/$className"

    fun toLogComponent(): String = componentKey()
}

enum class UsageAccessStatus {
    GRANTED,
    DENIED,
    QUERY_ERROR,
}

internal data class RawUsageEvent(
    val eventType: Int,
    val packageName: String?,
    val className: String?,
    val timeStamp: Long,
)
