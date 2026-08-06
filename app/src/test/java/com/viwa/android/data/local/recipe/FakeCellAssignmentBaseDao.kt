package com.viwa.android.data.local.recipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeCellAssignmentBaseDao : CellAssignmentBaseDao {
    private val rows = linkedMapOf<String, CellAssignmentBaseEntity>()
    private val snapshot = MutableStateFlow<List<CellAssignmentBaseEntity>>(emptyList())

    override suspend fun findByCellUuid(cellUuid: String): CellAssignmentBaseEntity? = rows[cellUuid]

    override suspend fun findAll(): List<CellAssignmentBaseEntity> = rows.values.sortedBy { it.cellUuid }

    override fun observeAll(): Flow<List<CellAssignmentBaseEntity>> = snapshot

    override suspend fun upsert(entity: CellAssignmentBaseEntity) {
        rows[entity.cellUuid] = entity
        snapshot.update { rows.values.sortedBy { it.cellUuid } }
    }

    fun allRows(): List<CellAssignmentBaseEntity> = rows.values.toList()
}
