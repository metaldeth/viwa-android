package com.viwa.android.data.local.entitlement

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineUsageLedgerStore
@Inject
constructor(
    private val dao: OfflineUsageLedgerDao,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun insert(entity: OfflineUsageLedgerEntity) {
        dao.insert(entity)
    }

    suspend fun update(entity: OfflineUsageLedgerEntity) {
        dao.update(entity.copy(updatedAtMs = clock()))
    }

    suspend fun findByRequestUuid(requestUuid: String): OfflineUsageLedgerEntity? =
        dao.findByRequestUuid(requestUuid)

    suspend fun listNonRejectedForGrant(grantId: String): List<OfflineUsageLedgerEntity> =
        dao.listNonRejectedForGrant(grantId)

    suspend fun listAwaitingReconcile(limit: Int = 50): List<OfflineUsageLedgerEntity> =
        dao.listAwaitingReconcile(limit)

    suspend fun listUncertainStates(): List<OfflineUsageLedgerEntity> = dao.listUncertainStates()

    suspend fun metricsSnapshot(): OfflineLedgerMetrics {
        val awaiting = dao.countAwaitingReconcile()
        val reserved = dao.countByState(OfflineUsageLedgerState.RESERVED.name)
        val pouring = dao.countByState(OfflineUsageLedgerState.POURING.name)
        val conflict = dao.countByState(OfflineUsageLedgerState.CONFLICT.name)
        val rejected = dao.countByState(OfflineUsageLedgerState.REJECTED.name)
        return OfflineLedgerMetrics(
            awaitingReconcile = awaiting,
            reserved = reserved,
            pouring = pouring,
            conflict = conflict,
            rejected = rejected,
        )
    }

    data class OfflineLedgerMetrics(
        val awaitingReconcile: Int,
        val reserved: Int,
        val pouring: Int,
        val conflict: Int,
        val rejected: Int,
    )
}
