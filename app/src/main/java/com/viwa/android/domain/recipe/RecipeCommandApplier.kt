package com.viwa.android.domain.recipe

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeCommandApplier
@Inject
constructor(
    private val effectiveRecipeStore: CellEffectiveRecipeStore,
) {
    suspend fun apply(command: RecipeCommandDownlink): CellEffectiveRecipeStore.ManagedCommandApplyResult =
        effectiveRecipeStore.applyManagedCommand(command)

    suspend fun persistCancelWatermark(cellUuid: String, cancelThroughGeneration: Long): Long =
        effectiveRecipeStore.advanceCancelWatermark(cellUuid, cancelThroughGeneration)

    fun buildAck(result: CellEffectiveRecipeStore.ManagedCommandApplyResult): RecipeCommandAckEntry =
        when (result) {
            is CellEffectiveRecipeStore.ManagedCommandApplyResult.Processed -> result.ack
            is CellEffectiveRecipeStore.ManagedCommandApplyResult.Redelivered -> result.ack
        }
}
