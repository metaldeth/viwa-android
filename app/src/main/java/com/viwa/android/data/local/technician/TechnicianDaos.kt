package com.viwa.android.data.local.technician

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TechnicianAllowlistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TechnicianAllowlistEntity)

    @Query("SELECT * FROM technician_allowlist_cache WHERE fingerprint = :fingerprint AND revoked = 0 LIMIT 1")
    suspend fun findActiveByFingerprint(fingerprint: String): TechnicianAllowlistEntity?

    @Query("UPDATE technician_allowlist_cache SET revoked = 1, updated_at_ms = :updatedAtMs WHERE key_id = :keyId")
    suspend fun markRevokedByKeyId(keyId: String, updatedAtMs: Long): Int

    @Query("UPDATE technician_allowlist_cache SET revoked = 1, updated_at_ms = :updatedAtMs WHERE fingerprint = :fingerprint")
    suspend fun markRevokedByFingerprint(fingerprint: String, updatedAtMs: Long): Int

    @Query("SELECT COUNT(*) FROM technician_allowlist_cache WHERE revoked = 0")
    suspend fun countActive(): Int

    @Query("SELECT MIN(updated_at_ms) FROM technician_allowlist_cache WHERE revoked = 0")
    suspend fun oldestActiveUpdatedAtMs(): Long?
}

@Dao
interface TechnicianAllowlistStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TechnicianAllowlistStateEntity)

    @Query("SELECT * FROM technician_allowlist_state WHERE id = 1 LIMIT 1")
    suspend fun getState(): TechnicianAllowlistStateEntity?
}

@Dao
interface TechnicianAuditOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TechnicianAuditOutboxEntity): Long

    @Query(
        "SELECT * FROM technician_audit_outbox WHERE sync_status = :status ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun listByStatus(status: String, limit: Int): List<TechnicianAuditOutboxEntity>

    @Query("UPDATE technician_audit_outbox SET sync_status = :status, synced_at_ms = :syncedAtMs WHERE request_uuid = :requestUuid")
    suspend fun markSynced(requestUuid: String, status: String, syncedAtMs: Long): Int

    @Query("SELECT COUNT(*) FROM technician_audit_outbox WHERE sync_status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT * FROM technician_audit_outbox WHERE request_uuid = :requestUuid LIMIT 1")
    suspend fun findByRequestUuid(requestUuid: String): TechnicianAuditOutboxEntity?

    @Query(
        "DELETE FROM technician_audit_outbox WHERE sync_status IN (:statuses) " +
            "AND synced_at_ms IS NOT NULL AND synced_at_ms < :cutoffMs",
    )
    suspend fun purgeTerminalOlderThan(statuses: List<String>, cutoffMs: Long): Int
}
