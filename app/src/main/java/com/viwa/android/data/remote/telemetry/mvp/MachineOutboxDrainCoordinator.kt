package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.MachineOutboxEntryEntity
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.OutboxFeatureFlags
import com.viwa.android.data.local.outbox.OutboxRetryPolicy
import com.viwa.android.di.AppIoScope
import dagger.Lazy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

@Singleton
class MachineOutboxDrainCoordinator
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,
    private val apiClient: MvpTelemetryApiClient,
    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }
    private val random: kotlin.random.Random = kotlin.random.Random.Default
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()

    private val drainMutex = Mutex()
    private var periodicFlushJob: Job? = null
    private var ackedPurgeJob: Job? = null

    init {
        startPeriodicAckedPurge()
    }

    suspend fun onSessionActive(sessionGeneration: Long, reason: String = "session-active") {
        outboxStore.recoverInFlightToPending()
        schedulePeriodicFlush(sessionGeneration)
        drain(reason, sessionGeneration)
    }

    suspend fun onEnqueue() {
        val gen = wsManager.currentSessionGeneration()
        drain("enqueue", gen)
    }

    suspend fun onNetworkValidated() {
        val gen = wsManager.currentSessionGeneration()
        drain("network-validated", gen)
    }

    suspend fun handleOutboxError(entry: MachineOutboxEntryEntity, code: String, message: String) {
        outboxStore.markServerError(entry, code, message)
    }

    suspend fun handleUnprovenPourDedupAck(entry: MachineOutboxEntryEntity) {
        val rotated = outboxStore.rotateMessageIdForRetry(entry)
        Timber.w(
            "MachineOutboxDrain: unproven pour dedup ack — rotated messageId to ${rotated.messageId} " +
                "idempotencyKey=${rotated.idempotencyKey}",
        )
        drain("unproven-pour-dedup", wsManager.currentSessionGeneration())
    }

    fun stopPeriodicFlush() {
        periodicFlushJob?.cancel()
        periodicFlushJob = null
    }

    private fun startPeriodicAckedPurge() {
        ackedPurgeJob?.cancel()
        ackedPurgeJob =
            appScope.launch {
                while (isActive) {
                    delay(OutboxRetryPolicy.ACKED_PURGE_INTERVAL_MS)
                    runCatching {
                        val purged = outboxStore.purgeAckedOlderThan()
                        if (purged > 0) {
                            Timber.d("MachineOutboxDrain: periodic purge removed $purged ACKED rows")
                        }
                    }.onFailure { Timber.w(it, "MachineOutboxDrain: periodic ACKED purge failed") }
                }
            }
    }

    private fun schedulePeriodicFlush(sessionGeneration: Long) {
        periodicFlushJob?.cancel()
        periodicFlushJob =
            appScope.launch {
                while (isActive) {
                    delay(OutboxRetryPolicy.PERIODIC_FLUSH_MS)
                    if (wsManager.fsmPhase() != TelemetryConnectionPhase.Active) continue
                    if (wsManager.currentSessionGeneration() != sessionGeneration) continue
                    if (outboxStore.countPendingOrInFlight() == 0) continue
                    drain("periodic-30s", sessionGeneration)
                }
            }
    }

    suspend fun drain(reason: String, sessionGeneration: Long) {
        drainMutex.withLock {
            outboxStore.expireTimedOutInFlight()
            val pending = outboxStore.listDrainable()
            if (pending.isEmpty()) return
            Timber.d("MachineOutboxDrain: reason=$reason pending=${pending.size} gen=$sessionGeneration")

            val wsActive =
                wsManager.fsmPhase() == TelemetryConnectionPhase.Active &&
                    wsManager.currentSessionGeneration() == sessionGeneration

            if (wsActive) {
                pending.take(OutboxRetryPolicy.MAX_BATCH_SIZE).forEach { entry ->
                    sendViaWebSocket(entry, sessionGeneration)
                }
                return
            }

            if (shouldUseRestFallback(pending.first())) {
                sendViaRestBatch(pending.take(OutboxRetryPolicy.MAX_BATCH_SIZE), sessionGeneration)
            }
        }
    }

    private suspend fun sendViaWebSocket(
        entry: MachineOutboxEntryEntity,
        sessionGeneration: Long,
    ) {
        val inFlight = outboxStore.markInFlight(entry, sessionGeneration) ?: return
        val payload =
            runCatching {
                json.decodeFromString(JsonObject.serializer(), inFlight.payloadJson)
            }.getOrElse {
                outboxStore.markWsSendFailure(inFlight, "INVALID_PAYLOAD_JSON")
                return
            }
        val wsType = inFlight.kind
        wsManager
            .sendEnvelope(type = wsType, payload = payload, messageId = inFlight.messageId)
            .onSuccess {
                Timber.i("MachineOutboxDrain: WS sent kind=${inFlight.kind} messageId=${inFlight.messageId}")
            }.onFailure { error ->
                outboxStore.markWsSendFailure(inFlight, error.message ?: "WS_SEND_FAILED")
                Timber.w(error, "MachineOutboxDrain: WS send failed messageId=${inFlight.messageId}")
            }
    }

    private suspend fun sendViaRestBatch(
        entries: List<MachineOutboxEntryEntity>,
        sessionGeneration: Long,
    ) {
        if (!OutboxFeatureFlags.FEATURE_OUTBOX_REST_SYNC) {
            Timber.d("MachineOutboxDrain: REST fallback disabled by feature flag")
            return
        }
        val capability = wsManager.outboxBatchCapability()
        if (capability == null) {
            Timber.d("MachineOutboxDrain: no outboxBatch capability")
            return
        }
        if (!wsManager.isNetworkValidated()) {
            return
        }
        val token = bearerTokenProvider.resolveBearerToken() ?: run {
            Timber.w("MachineOutboxDrain: no bearer token for REST batch")
            return
        }
        val marked =
            entries.mapNotNull { entry ->
                outboxStore.markInFlight(entry, sessionGeneration)
            }
        if (marked.isEmpty()) return

        val batchId = UUID.randomUUID().toString()
        val request =
            MachineOutboxBatchRequestDto(
                batchId = batchId,
                entries =
                    marked.map { row ->
                        MachineOutboxBatchEntryDto(
                            kind = row.kind,
                            messageId = row.messageId,
                            idempotencyKey = row.idempotencyKey,
                            sentAt = TelemetryIsoTimestamps.nowUtc(),
                            payload = json.decodeFromString(JsonObject.serializer(), row.payloadJson),
                        )
                    },
            )
        apiClient
            .submitOutboxBatch(
                endpoint = capability.endpoint,
                bearerToken = token,
                request = request,
            ).onSuccess { response ->
                applyBatchResults(marked, response)
            }.onFailure { error ->
                marked.forEach { row ->
                    outboxStore.markWsSendFailure(row, error.message ?: "REST_BATCH_FAILED")
                }
                Timber.w(error, "MachineOutboxDrain: REST batch failed batchId=$batchId")
            }
    }

    internal suspend fun applyBatchResults(
        sent: List<MachineOutboxEntryEntity>,
        response: MachineOutboxBatchResponseDto,
    ) {
        val byMessageId = sent.associateBy { it.messageId }
        val ackedMessageIds = mutableListOf<String>()
        response.results.forEach { result ->
            val entry = byMessageId[result.messageId] ?: return@forEach
            when (result.status.lowercase()) {
                "acked", "idempotent" -> {
                    val kind = MachineOutboxKind.fromWire(entry.kind) ?: return@forEach
                    outboxStore.markAcked(messageId = result.messageId, kind = kind)
                    ackedMessageIds += result.messageId
                }
                "rejected" -> outboxStore.markServerError(entry, result.code ?: "REJECTED", "batch rejected")
                "retryable" -> outboxStore.markWsSendFailure(entry, result.code ?: "RETRYABLE")
                else -> outboxStore.markWsSendFailure(entry, "UNKNOWN_STATUS:${result.status}")
            }
        }
        val resultIds = response.results.map { it.messageId }.toSet()
        sent.filter { it.messageId !in resultIds }.forEach { missing ->
            outboxStore.markWsSendFailure(missing, "MISSING_BATCH_RESULT")
        }
        if (ackedMessageIds.isNotEmpty()) {
            runCatching {
                val purged = outboxStore.purgeAckedByMessageIds(ackedMessageIds)
                Timber.d("MachineOutboxDrain: post-batch purge removed $purged ACKED rows")
            }.onFailure { Timber.w(it, "MachineOutboxDrain: post-batch ACKED purge failed") }
        }
    }

    private fun shouldUseRestFallback(sample: MachineOutboxEntryEntity): Boolean {
        if (!OutboxFeatureFlags.FEATURE_OUTBOX_REST_SYNC) return false
        if (wsManager.outboxBatchCapability() == null) return false
        if (!wsManager.isNetworkValidated()) return false
        val wsDown = wsManager.fsmPhase() != TelemetryConnectionPhase.Active
        if (wsDown) return true
        return sample.wsAckFailures >= OutboxRetryPolicy.WS_ACK_FAILURES_BEFORE_REST
    }
}
