package com.viwa.android.data.local.technician

import com.viwa.android.domain.technician.TechnicianKeyConstants
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class TechnicianAuditOutboxStore
@Inject
constructor(
    private val dao: TechnicianAuditOutboxDao,
) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun isPendingCapReached(): Boolean =
        dao.countByStatus(TechnicianAuditOutboxEntity.SYNC_PENDING) >=
            TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS

    suspend fun enqueue(entry: TechnicianAuditOutboxEntity): Boolean {
        if (isPendingCapReached()) {
            Timber.tag(TAG).w(
                "audit outbox pending cap reached (%d) — rejecting enqueue requestUuid=%s",
                TechnicianKeyConstants.MAX_PENDING_AUDIT_RECORDS,
                entry.requestUuid,
            )
            return false
        }
        return runCatching {
            dao.insert(entry)
            true
        }.getOrElse { false }
    }

    suspend fun enqueueNew(
        requestUuid: String,
        fingerprint: String,
        technicianKeyId: String?,
        action: String,
        channel: String,
        outcome: String,
        failureCode: String? = null,
    ): Boolean {
        val existing = dao.findByRequestUuid(requestUuid)
        if (existing != null) return false
        return enqueue(
            TechnicianAuditOutboxEntity(
                requestUuid = requestUuid,
                fingerprint = fingerprint,
                technicianKeyId = technicianKeyId,
                action = action,
                channel = channel,
                outcome = outcome,
                failureCode = failureCode,
                createdAtMs = clock(),
            ),
        )
    }

    suspend fun listPending(limit: Int = 50): List<TechnicianAuditOutboxEntity> =
        dao.listByStatus(TechnicianAuditOutboxEntity.SYNC_PENDING, limit)

    suspend fun markSynced(requestUuid: String, status: String) {
        dao.markSynced(requestUuid, status, clock())
    }

    suspend fun pendingCount(): Int = dao.countByStatus(TechnicianAuditOutboxEntity.SYNC_PENDING)

    suspend fun findByRequestUuid(requestUuid: String): TechnicianAuditOutboxEntity? =
        dao.findByRequestUuid(requestUuid)

    suspend fun purgeTerminalOlderThan(retentionMs: Long): Int {
        val cutoffMs = clock() - retentionMs
        return dao.purgeTerminalOlderThan(
            statuses =
                listOf(
                    TechnicianAuditOutboxEntity.SYNC_SYNCED,
                    TechnicianAuditOutboxEntity.SYNC_REJECTED,
                ),
            cutoffMs = cutoffMs,
        )
    }

    companion object {
        private const val TAG = "TechAuditOutbox"
    }
}
