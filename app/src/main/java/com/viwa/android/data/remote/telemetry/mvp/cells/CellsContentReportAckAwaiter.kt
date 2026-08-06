package com.viwa.android.data.remote.telemetry.mvp.cells

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

@Singleton
class CellsContentReportAckAwaiter
@Inject
constructor() {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

    fun register(messageId: String): CompletableDeferred<Result<Unit>> {
        val deferred = CompletableDeferred<Result<Unit>>()
        pending[messageId] = deferred
        return deferred
    }

    fun completeAck(
        correlationId: String,
        payload: JsonObject,
    ) {
        val deferred = pending.remove(correlationId) ?: return
        deferred.complete(
            CellsContentReportAckSemantics.parseAck(payload).map { },
        )
    }

    fun completeError(
        correlationId: String?,
        message: String,
    ) {
        if (correlationId.isNullOrBlank()) return
        pending.remove(correlationId)?.complete(Result.failure(IllegalStateException(message)))
    }

    fun cancel(messageId: String) {
        pending.remove(messageId)?.cancel()
    }

    /** Clears all in-flight operator content ack waits (WS disconnect / session reset). */
    fun cancelAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
