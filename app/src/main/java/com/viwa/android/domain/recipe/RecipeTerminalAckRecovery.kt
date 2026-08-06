package com.viwa.android.domain.recipe

import com.viwa.android.data.local.recipe.CellEffectiveRecipeEntity
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry

/** Reconstruct wire ack from persisted Room terminal columns (task-17 resilience). */
object RecipeTerminalAckRecovery {
    fun hasPersistedTerminal(entity: CellEffectiveRecipeEntity): Boolean =
        !entity.lastAppliedCommandId.isNullOrBlank() &&
            !entity.lastTerminalAckStatus.isNullOrBlank() &&
            entity.lastTerminalCommandGeneration > 0L

    fun toAckEntry(entity: CellEffectiveRecipeEntity): RecipeCommandAckEntry? {
        if (!hasPersistedTerminal(entity)) return null
        val domain = entity.toDomain()
        val appliedRecipe =
            when (entity.lastTerminalAckStatus) {
                RecipeCommandAckStatus.APPLIED -> domain.triple?.takeIf { domain.isRecipeComplete }
                else -> null
            }
        return RecipeCommandAckEntry(
            commandId = entity.lastAppliedCommandId!!,
            commandGeneration = entity.lastTerminalCommandGeneration,
            cellUuid = entity.cellId,
            status = entity.lastTerminalAckStatus!!,
            failureCode = entity.lastTerminalAckFailureCode,
            appliedRecipe = appliedRecipe,
        )
    }
}
