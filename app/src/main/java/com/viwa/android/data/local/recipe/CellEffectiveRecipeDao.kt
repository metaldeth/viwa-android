package com.viwa.android.data.local.recipe

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CellEffectiveRecipeDao {
    @Query("SELECT * FROM cell_effective_recipe WHERE cell_id = :cellId LIMIT 1")
    suspend fun findByCellId(cellId: String): CellEffectiveRecipeEntity?

    @Query("SELECT * FROM cell_effective_recipe ORDER BY cell_id ASC")
    fun observeAll(): Flow<List<CellEffectiveRecipeEntity>>

    @Query("SELECT * FROM cell_effective_recipe ORDER BY cell_id ASC")
    suspend fun findAll(): List<CellEffectiveRecipeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CellEffectiveRecipeEntity)

    @Query(
        """
        SELECT * FROM cell_effective_recipe
        WHERE last_applied_command_id IS NOT NULL
        AND last_terminal_ack_status IS NOT NULL
        AND last_terminal_command_generation > 0
        AND terminal_ack_delivered = 0
        ORDER BY cell_id ASC
        """,
    )
    suspend fun findUndeliveredTerminalAcks(): List<CellEffectiveRecipeEntity>

    @Query(
        """
        UPDATE cell_effective_recipe
        SET terminal_ack_delivered = 1, updated_at_ms = :updatedAtMs
        WHERE cell_id = :cellId
        AND last_applied_command_id = :commandId
        AND last_terminal_ack_status = :status
        AND last_terminal_command_generation = :commandGeneration
        """,
    )
    suspend fun markTerminalAckDelivered(
        cellId: String,
        commandId: String,
        status: String,
        commandGeneration: Long,
        updatedAtMs: Long,
    ): Int
}
