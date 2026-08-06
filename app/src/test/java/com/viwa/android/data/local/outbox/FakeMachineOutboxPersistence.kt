package com.viwa.android.data.local.outbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory outbox persistence for JVM unit tests. */
class FakeMachineOutboxPersistence : MachineOutboxPersistence {
    private val mutex = Mutex()
    private val rows = linkedMapOf<String, MachineOutboxEntryEntity>()

    override suspend fun insert(entry: MachineOutboxEntryEntity): Long =
        mutex.withLock {
            val duplicate =
                rows.values.any {
                    it.kind == entry.kind && it.idempotencyKey == entry.idempotencyKey
                }
            if (duplicate) return -1L
            rows[entry.localId] = entry
            return 1L
        }

    override suspend fun update(entry: MachineOutboxEntryEntity) {
        mutex.withLock {
            rows[entry.localId] = entry
        }
    }

    override suspend fun listDrainable(nowMs: Long, limit: Int): List<MachineOutboxEntryEntity> =
        mutex.withLock {
            rows.values
                .filter {
                    (it.status == MachineOutboxStatus.PENDING.name ||
                        it.status == MachineOutboxStatus.IN_FLIGHT.name) &&
                        it.nextRetryAtMs <= nowMs
                }.sortedWith(
                    compareBy<MachineOutboxEntryEntity> { row ->
                        MachineOutboxKind.fromWire(row.kind)?.drainPriority ?: 2
                    }.thenBy { it.createdAtMs }
                        .thenBy { it.localId },
                ).take(limit)
        }

    override suspend fun findByMessageId(messageId: String): MachineOutboxEntryEntity? =
        mutex.withLock { rows.values.firstOrNull { it.messageId == messageId } }

    override suspend fun findByKindAndIdempotencyKey(
        kind: String,
        idempotencyKey: String,
    ): MachineOutboxEntryEntity? =
        mutex.withLock {
            rows.values.firstOrNull { it.kind == kind && it.idempotencyKey == idempotencyKey }
        }

    override suspend fun countPendingOrInFlight(): Int =
        mutex.withLock {
            rows.values.count {
                it.status == MachineOutboxStatus.PENDING.name ||
                    it.status == MachineOutboxStatus.IN_FLIGHT.name
            }
        }

    override suspend fun hasUnsentRecipeEntries(nowMs: Long): Boolean =
        mutex.withLock {
            rows.values.any {
                it.kind in RECIPE_KIND_WIRE &&
                    it.status == MachineOutboxStatus.PENDING.name &&
                    it.nextRetryAtMs <= nowMs
            }
        }

    override suspend fun hasUnsentRecipeReportForCell(cellId: String, nowMs: Long): Boolean =
        mutex.withLock {
            val prefix = "$cellId|"
            rows.values.any {
                it.kind == MachineOutboxKind.CELLS_RECIPE_REPORT.wireValue &&
                    (it.status == MachineOutboxStatus.PENDING.name ||
                        it.status == MachineOutboxStatus.IN_FLIGHT.name) &&
                    it.nextRetryAtMs <= nowMs &&
                    it.idempotencyKey.startsWith(prefix)
            }
        }

    override suspend fun recoverAllInFlightToPending(): Int =
        mutex.withLock {
            var count = 0
            rows.entries.forEach { (id, row) ->
                if (row.status == MachineOutboxStatus.IN_FLIGHT.name) {
                    rows[id] =
                        row.copy(
                            status = MachineOutboxStatus.PENDING.name,
                            inFlightSinceMs = null,
                        )
                    count++
                }
            }
            count
        }

    override suspend fun purgeAckedBefore(beforeMs: Long): Int =
        mutex.withLock {
            val toRemove =
                rows.entries.filter { (_, row) ->
                    row.status == MachineOutboxStatus.ACKED.name &&
                        row.ackedAtMs != null &&
                        row.ackedAtMs < beforeMs
                }.map { it.key }
            toRemove.forEach { rows.remove(it) }
            toRemove.size
        }

    override suspend fun deleteAckedByMessageIds(messageIds: List<String>): Int =
        mutex.withLock {
            if (messageIds.isEmpty()) return 0
            val idSet = messageIds.toSet()
            val toRemove =
                rows.entries.filter { (_, row) ->
                    row.status == MachineOutboxStatus.ACKED.name && row.messageId in idSet
                }.map { it.key }
            toRemove.forEach { rows.remove(it) }
            toRemove.size
        }

    fun allRows(): List<MachineOutboxEntryEntity> = rows.values.toList()

    private companion object {
        val RECIPE_KIND_WIRE =
            setOf(
                MachineOutboxKind.CELLS_RECIPE_REPORT.wireValue,
                MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK.wireValue,
            )
    }
}
