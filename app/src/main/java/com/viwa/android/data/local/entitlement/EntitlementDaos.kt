package com.viwa.android.data.local.entitlement

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EntitlementCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EntitlementCacheEntity)

    @Query("SELECT * FROM entitlement_cache WHERE subject_hash = :subjectHash AND machine_id = :machineId LIMIT 1")
    suspend fun findBySubjectAndMachine(subjectHash: String, machineId: String): EntitlementCacheEntity?

    @Query("SELECT * FROM entitlement_cache WHERE grant_id = :grantId LIMIT 1")
    suspend fun findByGrantId(grantId: String): EntitlementCacheEntity?

    @Query("UPDATE entitlement_cache SET revoked = 1, updated_at_ms = :updatedAtMs WHERE grant_id = :grantId")
    suspend fun markRevoked(grantId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE entitlement_cache SET revoked = 1, updated_at_ms = :updatedAtMs " +
            "WHERE subject_hash = :subjectHash AND machine_id = :machineId",
    )
    suspend fun invalidateSubject(subjectHash: String, machineId: String, updatedAtMs: Long): Int

    @Query("SELECT COUNT(*) FROM entitlement_cache WHERE revoked = 0 AND expires_at_ms > :nowMs")
    suspend fun countActive(nowMs: Long): Int

    @Query("SELECT MIN(updated_at_ms) FROM entitlement_cache WHERE revoked = 0")
    suspend fun oldestActiveUpdatedAtMs(): Long?
}

@Dao
interface OfflineUsageLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OfflineUsageLedgerEntity): Long

    @Update
    suspend fun update(entity: OfflineUsageLedgerEntity)

    @Query("SELECT * FROM offline_usage_ledger WHERE request_uuid = :requestUuid LIMIT 1")
    suspend fun findByRequestUuid(requestUuid: String): OfflineUsageLedgerEntity?

    @Query(
        "SELECT * FROM offline_usage_ledger WHERE grant_id = :grantId AND state NOT IN ('REJECTED', 'CONFLICT')",
    )
    suspend fun listNonRejectedForGrant(grantId: String): List<OfflineUsageLedgerEntity>

    @Query(
        "SELECT * FROM offline_usage_ledger WHERE state IN ('FINALIZED', 'ENQUEUED') ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun listAwaitingReconcile(limit: Int): List<OfflineUsageLedgerEntity>

    @Query("SELECT * FROM offline_usage_ledger WHERE state IN ('RESERVED', 'POURING')")
    suspend fun listUncertainStates(): List<OfflineUsageLedgerEntity>

    @Query("SELECT COUNT(*) FROM offline_usage_ledger WHERE state = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT COUNT(*) FROM offline_usage_ledger WHERE state IN ('FINALIZED', 'ENQUEUED')")
    suspend fun countAwaitingReconcile(): Int
}
