package com.viwa.android.data.local.recipe

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CellAssignmentBaseDao {
    @Query("SELECT * FROM cell_assignment_base WHERE cell_uuid = :cellUuid LIMIT 1")
    suspend fun findByCellUuid(cellUuid: String): CellAssignmentBaseEntity?

    @Query("SELECT * FROM cell_assignment_base")
    suspend fun findAll(): List<CellAssignmentBaseEntity>

    @Query("SELECT * FROM cell_assignment_base")
    fun observeAll(): Flow<List<CellAssignmentBaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CellAssignmentBaseEntity)
}
