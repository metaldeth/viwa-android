package com.viwa.android.data.local.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MachineOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: MachineOutboxEntryEntity): Long

    @Update
    suspend fun update(entry: MachineOutboxEntryEntity)

    @Query(
        """
        SELECT * FROM machine_outbox
        WHERE status IN ('PENDING', 'IN_FLIGHT')
        AND next_retry_at_ms <= :nowMs
        ORDER BY created_at_ms ASC
        LIMIT :limit
        """,
    )
    suspend fun listDrainable(nowMs: Long, limit: Int = 50): List<MachineOutboxEntryEntity>

    @Query("SELECT * FROM machine_outbox WHERE message_id = :messageId LIMIT 1")
    suspend fun findByMessageId(messageId: String): MachineOutboxEntryEntity?

    @Query(
        """
        SELECT * FROM machine_outbox
        WHERE kind = :kind AND idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    suspend fun findByKindAndIdempotencyKey(kind: String, idempotencyKey: String): MachineOutboxEntryEntity?

    @Query("SELECT COUNT(*) FROM machine_outbox WHERE status IN ('PENDING', 'IN_FLIGHT')")
    suspend fun countPendingOrInFlight(): Int

    @Query(
        """
        UPDATE machine_outbox
        SET status = 'PENDING', in_flight_since_ms = NULL
        WHERE status = 'IN_FLIGHT'
        """,
    )
    suspend fun recoverAllInFlightToPending(): Int

    @Query("DELETE FROM machine_outbox WHERE status = 'ACKED' AND acked_at_ms IS NOT NULL AND acked_at_ms < :beforeMs")
    suspend fun purgeAckedBefore(beforeMs: Long): Int

    @Query("DELETE FROM machine_outbox WHERE status = 'ACKED' AND message_id IN (:messageIds)")
    suspend fun deleteAckedByMessageIds(messageIds: List<String>): Int
}
