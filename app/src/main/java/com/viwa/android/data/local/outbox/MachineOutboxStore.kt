package com.viwa.android.data.local.outbox

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

@Singleton
class MachineOutboxStore
@Inject
constructor(
    persistence: MachineOutboxPersistence,
    private val configRepository: ConfigRepository,
    private val migrator: PendingSalesOutboxMigrator,
) {
    private val dao = persistence
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val random: kotlin.random.Random = kotlin.random.Random.Default
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val migrationMutex = Mutex()
    private var migrationDone = false

    suspend fun listDrainable(limit: Int = OutboxRetryPolicy.MAX_BATCH_SIZE): List<MachineOutboxEntryEntity> {
        ensureMigrated()
        return dao.listDrainable(clock(), limit)
    }

    suspend fun countPendingOrInFlight(): Int {
        ensureMigrated()
        return dao.countPendingOrInFlight()
    }

    suspend fun markInFlight(
        entry: MachineOutboxEntryEntity,
        sessionGeneration: Long,
    ): MachineOutboxEntryEntity? {
        if (entry.status != MachineOutboxStatus.PENDING.name &&
            entry.status != MachineOutboxStatus.IN_FLIGHT.name
        ) {
            return null
        }
        val now = clock()
        val updated =
            entry.copy(
                status = MachineOutboxStatus.IN_FLIGHT.name,
                sessionGenerationAtSend = sessionGeneration,
                inFlightSinceMs = now,
            )
        dao.update(updated)
        return updated
    }

    suspend fun markAcked(
        messageId: String? = null,
        idempotencyKey: String? = null,
        kind: MachineOutboxKind,
    ): Boolean {
        val entry =
            when {
                !messageId.isNullOrBlank() -> dao.findByMessageId(messageId)
                !idempotencyKey.isNullOrBlank() ->
                    dao.findByKindAndIdempotencyKey(kind.wireValue, idempotencyKey)
                else -> null
            } ?: return false
        if (entry.status == MachineOutboxStatus.ACKED.name) return true
        val now = clock()
        dao.update(
            entry.copy(
                status = MachineOutboxStatus.ACKED.name,
                ackedAtMs = now,
                lastError = null,
                inFlightSinceMs = null,
            ),
        )
        return true
    }

    suspend fun markWsSendFailure(entry: MachineOutboxEntryEntity, error: String): MachineOutboxEntryEntity {
        val attempts = entry.attempts + 1
        val now = clock()
        val nextRetry = now + OutboxRetryPolicy.nextRetryDelayMs(attempts, random)
        val updated =
            if (OutboxRetryPolicy.shouldMarkDead(attempts)) {
                entry.copy(
                    status = MachineOutboxStatus.DEAD.name,
                    attempts = attempts,
                    nextRetryAtMs = Long.MAX_VALUE,
                    lastError = error,
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            } else {
                entry.copy(
                    status = MachineOutboxStatus.PENDING.name,
                    attempts = attempts,
                    nextRetryAtMs = nextRetry,
                    lastError = error,
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            }
        dao.update(updated)
        return updated
    }

    suspend fun markWsAckTimeout(entry: MachineOutboxEntryEntity): MachineOutboxEntryEntity {
        val attempts = entry.attempts + 1
        val wsAckFailures = entry.wsAckFailures + 1
        val now = clock()
        val nextRetry = now + OutboxRetryPolicy.nextRetryDelayMs(attempts, random)
        val updated =
            if (OutboxRetryPolicy.shouldMarkDead(attempts)) {
                entry.copy(
                    status = MachineOutboxStatus.DEAD.name,
                    attempts = attempts,
                    wsAckFailures = wsAckFailures,
                    nextRetryAtMs = Long.MAX_VALUE,
                    lastError = "WS_ACK_TIMEOUT",
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            } else {
                entry.copy(
                    status = MachineOutboxStatus.PENDING.name,
                    attempts = attempts,
                    wsAckFailures = wsAckFailures,
                    nextRetryAtMs = nextRetry,
                    lastError = "WS_ACK_TIMEOUT",
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            }
        dao.update(updated)
        return updated
    }

    suspend fun markServerError(
        entry: MachineOutboxEntryEntity,
        code: String,
        message: String,
    ): MachineOutboxEntryEntity {
        val attempts = entry.attempts + 1
        val now = clock()
        val combined = "$code: $message"
        val updated =
            if (OutboxRetryPolicy.isTerminalError(code)) {
                entry.copy(
                    status = MachineOutboxStatus.REJECTED.name,
                    attempts = attempts,
                    nextRetryAtMs = Long.MAX_VALUE,
                    lastError = combined,
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            } else if (OutboxRetryPolicy.shouldMarkDead(attempts)) {
                entry.copy(
                    status = MachineOutboxStatus.DEAD.name,
                    attempts = attempts,
                    nextRetryAtMs = Long.MAX_VALUE,
                    lastError = combined,
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            } else {
                entry.copy(
                    status = MachineOutboxStatus.PENDING.name,
                    attempts = attempts,
                    wsAckFailures = entry.wsAckFailures + 1,
                    nextRetryAtMs = now + OutboxRetryPolicy.nextRetryDelayMs(attempts, random),
                    lastError = combined,
                    inFlightSinceMs = null,
                    sessionGenerationAtSend = null,
                )
            }
        dao.update(updated)
        return updated
    }

    suspend fun recoverInFlightToPending(): Int {
        ensureMigrated()
        return dao.recoverAllInFlightToPending()
    }

    suspend fun findByMessageId(messageId: String): MachineOutboxEntryEntity? {
        ensureMigrated()
        return dao.findByMessageId(messageId)
    }

    suspend fun findByKindAndIdempotencyKey(
        kind: MachineOutboxKind,
        idempotencyKey: String,
    ): MachineOutboxEntryEntity? {
        ensureMigrated()
        return dao.findByKindAndIdempotencyKey(kind.wireValue, idempotencyKey)
    }

    suspend fun enqueueRaw(row: MachineOutboxEntryEntity): EnqueueResult {
        ensureMigrated()
        val existing = dao.findByKindAndIdempotencyKey(row.kind, row.idempotencyKey)
        if (existing != null) {
            return EnqueueResult.Duplicate(existing.localId)
        }
        val inserted = dao.insert(row)
        return if (inserted == -1L) {
            EnqueueResult.Duplicate(
                dao.findByKindAndIdempotencyKey(row.kind, row.idempotencyKey)?.localId,
            )
        } else {
            EnqueueResult.Inserted(row.localId)
        }
    }

    suspend fun expireTimedOutInFlight(nowMs: Long = clock()): List<MachineOutboxEntryEntity> {
        ensureMigrated()
        val timedOut =
            dao.listDrainable(nowMs, OutboxRetryPolicy.MAX_BATCH_SIZE)
                .filter { row ->
                    row.status == MachineOutboxStatus.IN_FLIGHT.name &&
                        row.inFlightSinceMs != null &&
                        nowMs - row.inFlightSinceMs >= OutboxRetryPolicy.ACK_TIMEOUT_MS
                }
        return timedOut.map { markWsAckTimeout(it) }
    }

    suspend fun purgeAckedOlderThan(
        retentionMs: Long = OutboxRetryPolicy.ACKED_RETENTION_MS,
        nowMs: Long = clock(),
    ): Int {
        ensureMigrated()
        return dao.purgeAckedBefore(nowMs - retentionMs)
    }

    suspend fun purgeAckedByMessageIds(messageIds: Collection<String>): Int {
        ensureMigrated()
        if (messageIds.isEmpty()) return 0
        return dao.deleteAckedByMessageIds(messageIds.toList())
    }

    private suspend fun ensureMigrated() {
        if (migrationDone) return
        migrationMutex.withLock {
            if (migrationDone) return
            migrator.migrateIfNeeded()
            dao.recoverAllInFlightToPending()
            migrationDone = true
        }
    }

    sealed class EnqueueResult {
        data class Inserted(val localId: String) : EnqueueResult()

        data class Duplicate(val existingLocalId: String?) : EnqueueResult()
    }
}

@Singleton
class PendingSalesOutboxMigrator
@Inject
constructor(
    persistence: MachineOutboxPersistence,
    private val configRepository: ConfigRepository,
) {
    private val dao = persistence
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun migrateIfNeeded() {
        if (configRepository.get(JsonStoreKeys.OUTBOX_PENDING_SALES_IMPORTED) == "true") {
            return
        }
        // Telemetry v3 breaks sale.report compatibility; legacy JsonStore rows are not migrated.
        markImported()
        Timber.i("PendingSalesOutboxMigrator: skipped legacy sale.report import (telemetry v3)")
    }

    private suspend fun markImported() {
        configRepository.set(JsonStoreKeys.OUTBOX_PENDING_SALES_IMPORTED, "true")
    }

    companion object {
        private const val TAG = "PendingSalesMigrator"
    }
}
