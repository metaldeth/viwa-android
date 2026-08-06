package com.viwa.android.data.local.recipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeCellEffectiveRecipeDao : CellEffectiveRecipeDao {
    private val rows = linkedMapOf<String, CellEffectiveRecipeEntity>()
    private val snapshot = MutableStateFlow<List<CellEffectiveRecipeEntity>>(emptyList())

    override suspend fun findByCellId(cellId: String): CellEffectiveRecipeEntity? = rows[cellId]

    override fun observeAll(): Flow<List<CellEffectiveRecipeEntity>> = snapshot

    override suspend fun findAll(): List<CellEffectiveRecipeEntity> = rows.values.sortedBy { it.cellId }

    override suspend fun upsert(entity: CellEffectiveRecipeEntity) {
        rows[entity.cellId] = entity
        snapshot.update { rows.values.sortedBy { it.cellId } }
    }

    override suspend fun findUndeliveredTerminalAcks(): List<CellEffectiveRecipeEntity> =
        rows.values
            .filter {
                !it.lastAppliedCommandId.isNullOrBlank() &&
                    !it.lastTerminalAckStatus.isNullOrBlank() &&
                    it.lastTerminalCommandGeneration > 0L &&
                    !it.terminalAckDelivered
            }.sortedBy { it.cellId }

    override suspend fun markTerminalAckDelivered(
        cellId: String,
        commandId: String,
        status: String,
        commandGeneration: Long,
        updatedAtMs: Long,
    ): Int {
        val current = rows[cellId] ?: return 0
        if (
            current.lastAppliedCommandId != commandId ||
            current.lastTerminalAckStatus != status ||
            current.lastTerminalCommandGeneration != commandGeneration ||
            current.terminalAckDelivered
        ) {
            return 0
        }
        rows[cellId] =
            current.copy(
                terminalAckDelivered = true,
                updatedAtMs = updatedAtMs,
            )
        snapshot.update { rows.values.sortedBy { it.cellId } }
        return 1
    }

    fun allRows(): List<CellEffectiveRecipeEntity> = rows.values.toList()
}
