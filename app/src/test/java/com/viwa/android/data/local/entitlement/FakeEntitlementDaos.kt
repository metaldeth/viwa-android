package com.viwa.android.data.local.entitlement

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeEntitlementCacheDao : EntitlementCacheDao {
    private val mutex = Mutex()
    private val rows = linkedMapOf<Pair<String, String>, EntitlementCacheEntity>()

    override suspend fun upsert(entity: EntitlementCacheEntity) {
        mutex.withLock {
            rows[entity.subjectHash to entity.machineId] = entity
        }
    }

    override suspend fun findBySubjectAndMachine(subjectHash: String, machineId: String): EntitlementCacheEntity? =
        mutex.withLock { rows[subjectHash to machineId] }

    override suspend fun findByGrantId(grantId: String): EntitlementCacheEntity? =
        mutex.withLock { rows.values.firstOrNull { it.grantId == grantId } }

    override suspend fun markRevoked(grantId: String, updatedAtMs: Long): Int =
        mutex.withLock {
            val key = rows.entries.firstOrNull { it.value.grantId == grantId }?.key ?: return 0
            val row = rows[key] ?: return 0
            rows[key] = row.copy(revoked = true, updatedAtMs = updatedAtMs)
            1
        }

    override suspend fun invalidateSubject(subjectHash: String, machineId: String, updatedAtMs: Long): Int =
        mutex.withLock {
            val key = subjectHash to machineId
            val row = rows[key] ?: return 0
            rows[key] = row.copy(revoked = true, updatedAtMs = updatedAtMs)
            1
        }

    override suspend fun countActive(nowMs: Long): Int =
        mutex.withLock { rows.values.count { !it.revoked && it.expiresAtMs > nowMs } }

    override suspend fun oldestActiveUpdatedAtMs(): Long? =
        mutex.withLock { rows.values.filter { !it.revoked }.minOfOrNull { it.updatedAtMs } }
}

class FakeOfflineUsageLedgerDao : OfflineUsageLedgerDao {
    private val mutex = Mutex()
    private val rows = linkedMapOf<String, OfflineUsageLedgerEntity>()

    override suspend fun insert(entity: OfflineUsageLedgerEntity): Long =
        mutex.withLock {
            if (rows.containsKey(entity.requestUuid)) return -1L
            rows[entity.requestUuid] = entity
            1L
        }

    override suspend fun update(entity: OfflineUsageLedgerEntity) {
        mutex.withLock { rows[entity.requestUuid] = entity }
    }

    override suspend fun findByRequestUuid(requestUuid: String): OfflineUsageLedgerEntity? =
        mutex.withLock { rows[requestUuid] }

    override suspend fun listNonRejectedForGrant(grantId: String): List<OfflineUsageLedgerEntity> =
        mutex.withLock {
            rows.values.filter { it.grantId == grantId && it.state !in listOf("REJECTED", "CONFLICT") }
        }

    override suspend fun listAwaitingReconcile(limit: Int): List<OfflineUsageLedgerEntity> =
        mutex.withLock {
            rows.values.filter { it.state in listOf("FINALIZED", "ENQUEUED") }.take(limit)
        }

    override suspend fun listUncertainStates(): List<OfflineUsageLedgerEntity> =
        mutex.withLock { rows.values.filter { it.state in listOf("RESERVED", "POURING") } }

    override suspend fun countByState(state: String): Int =
        mutex.withLock { rows.values.count { it.state == state } }

    override suspend fun countAwaitingReconcile(): Int =
        mutex.withLock { rows.values.count { it.state in listOf("FINALIZED", "ENQUEUED") } }
}
