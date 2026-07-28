package com.viwa.android.data.local.technician

class FakeTechnicianAllowlistDao : TechnicianAllowlistDao {
    private val rows = linkedMapOf<String, TechnicianAllowlistEntity>()

    override suspend fun upsert(entity: TechnicianAllowlistEntity) {
        rows[entity.fingerprint] = entity
    }

    override suspend fun findActiveByFingerprint(fingerprint: String): TechnicianAllowlistEntity? =
        rows[fingerprint]?.takeUnless { it.revoked }

    override suspend fun markRevokedByKeyId(keyId: String, updatedAtMs: Long): Int {
        var count = 0
        rows.values.forEach { entity ->
            if (entity.keyId == keyId && !entity.revoked) {
                rows[entity.fingerprint] = entity.copy(revoked = true, updatedAtMs = updatedAtMs)
                count++
            }
        }
        return count
    }

    override suspend fun markRevokedByFingerprint(fingerprint: String, updatedAtMs: Long): Int {
        val entity = rows[fingerprint] ?: return 0
        rows[fingerprint] = entity.copy(revoked = true, updatedAtMs = updatedAtMs)
        return 1
    }

    override suspend fun countActive(): Int = rows.values.count { !it.revoked }

    override suspend fun oldestActiveUpdatedAtMs(): Long? =
        rows.values.filter { !it.revoked }.minOfOrNull { it.updatedAtMs }
}

class FakeTechnicianAllowlistStateDao : TechnicianAllowlistStateDao {
    private var state: TechnicianAllowlistStateEntity? = null

    override suspend fun upsert(entity: TechnicianAllowlistStateEntity) {
        state = entity
    }

    override suspend fun getState(): TechnicianAllowlistStateEntity? = state
}

class FakeTechnicianAuditOutboxDao : TechnicianAuditOutboxDao {
    val rows = linkedMapOf<String, TechnicianAuditOutboxEntity>()

    override suspend fun insert(entity: TechnicianAuditOutboxEntity): Long {
        require(entity.requestUuid !in rows) { "duplicate requestUuid" }
        rows[entity.requestUuid] = entity
        return 1L
    }

    override suspend fun listByStatus(status: String, limit: Int): List<TechnicianAuditOutboxEntity> =
        rows.values.filter { it.syncStatus == status }.sortedBy { it.createdAtMs }.take(limit)

    override suspend fun markSynced(requestUuid: String, status: String, syncedAtMs: Long): Int {
        val entity = rows[requestUuid] ?: return 0
        rows[requestUuid] = entity.copy(syncStatus = status, syncedAtMs = syncedAtMs)
        return 1
    }

    override suspend fun countByStatus(status: String): Int = rows.values.count { it.syncStatus == status }

    override suspend fun findByRequestUuid(requestUuid: String): TechnicianAuditOutboxEntity? = rows[requestUuid]

    override suspend fun purgeTerminalOlderThan(statuses: List<String>, cutoffMs: Long): Int {
        val toRemove =
            rows.filterValues { entity ->
                entity.syncStatus in statuses &&
                    entity.syncedAtMs != null &&
                    entity.syncedAtMs < cutoffMs
            }.keys
        toRemove.forEach { rows.remove(it) }
        return toRemove.size
    }
}
