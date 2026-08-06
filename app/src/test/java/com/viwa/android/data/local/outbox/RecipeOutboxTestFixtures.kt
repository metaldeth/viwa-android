package com.viwa.android.data.local.outbox

import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.recipe.RecipeCommandAckEmitter
import com.viwa.android.domain.recipe.RecipeCommandInbox

/** Shared JVM test wiring for recipe outbox + inbox (task-17). */
object RecipeOutboxTestFixtures {
    fun createOutboxStack(
        recipeDao: FakeCellEffectiveRecipeDao = FakeCellEffectiveRecipeDao(),
        recipeCodec: RecipeMessageCodec = RecipeMessageCodec(),
    ): RecipeOutboxTestStack {
        val persistence = FakeMachineOutboxPersistence()
        val config = EmptyConfigRepository()
        val machineOutboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = config,
                migrator = PendingSalesOutboxMigrator(persistence, config),
            )
        val recipeOutboxStore =
            RecipeOutboxStore(
                outboxStore = machineOutboxStore,
                recipeCodec = recipeCodec,
                effectiveRecipeDao = recipeDao,
            )
        return RecipeOutboxTestStack(
            persistence = persistence,
            machineOutboxStore = machineOutboxStore,
            recipeOutboxStore = recipeOutboxStore,
            recipeDao = recipeDao,
        )
    }

    data class RecipeOutboxTestStack(
        val persistence: FakeMachineOutboxPersistence,
        val machineOutboxStore: MachineOutboxStore,
        val recipeOutboxStore: RecipeOutboxStore,
        val recipeDao: FakeCellEffectiveRecipeDao,
    ) {
        fun inbox(
            applier: com.viwa.android.domain.recipe.RecipeCommandApplier,
            store: com.viwa.android.data.local.recipe.CellEffectiveRecipeStore,
        ): RecipeCommandInbox =
            RecipeCommandInbox(
                applier = applier,
                ackEmitter = RecipeCommandAckEmitter(recipeOutboxStore, drainCoordinator = null),
                recipeOutboxStore = recipeOutboxStore,
                effectiveRecipeStore = store,
            )
    }

    private class EmptyConfigRepository : ConfigRepository {
        override suspend fun get(key: String): String? = null

        override suspend fun set(key: String, value: String) = Unit

        override suspend fun delete(key: String) = Unit

        override suspend fun getJson(key: String): String? = null

        override suspend fun setJson(key: String, json: String) = Unit
    }
}
