package com.viwa.android.domain.recipe

import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.RecipeOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Persists terminal command acks durably before scheduling uplink (task-17). */
@Singleton
class RecipeCommandAckEmitter
@Inject
constructor(
    private val recipeOutboxStore: RecipeOutboxStore,
    private val drainCoordinatorLazy: Lazy<MachineOutboxDrainCoordinator>,
) {
    internal constructor(
        recipeOutboxStore: RecipeOutboxStore,
        drainCoordinator: MachineOutboxDrainCoordinator?,
    ) : this(
        recipeOutboxStore = recipeOutboxStore,
        drainCoordinatorLazy =
            object : Lazy<MachineOutboxDrainCoordinator> {
                override fun get(): MachineOutboxDrainCoordinator =
                    drainCoordinator ?: throw UnsupportedOperationException("drainCoordinator not configured")
            },
    ) {
        this.testDrainCoordinator = drainCoordinator
    }

    private var testDrainCoordinator: MachineOutboxDrainCoordinator? = null

    suspend fun emitAcks(acks: List<RecipeCommandAckEntry>) {
        if (acks.isEmpty()) return
        var inserted = 0
        acks.forEach { ack ->
            when (recipeOutboxStore.enqueueCommandAck(ack)) {
                is MachineOutboxStore.EnqueueResult.Inserted -> inserted++
                is MachineOutboxStore.EnqueueResult.Duplicate -> Unit
            }
        }
        if (inserted > 0) {
            scheduleDrain()
        }
    }

    suspend fun emitAck(ack: RecipeCommandAckEntry) {
        emitAcks(listOf(ack))
    }

    private suspend fun scheduleDrain() {
        val coordinator =
            testDrainCoordinator
                ?: runCatching { drainCoordinatorLazy.get() }.getOrNull()
        if (coordinator == null) return
        runCatching { coordinator.onEnqueue() }
            .onFailure { Timber.w(it, "RecipeCommandAckEmitter: outbox drain scheduling failed") }
    }
}

internal sealed class RecipeInboxEntry {
    data class SyncControl(val cells: List<RecipeSyncControlCell>) : RecipeInboxEntry()

    data class Command(val command: RecipeCommandDownlink) : RecipeInboxEntry()
}
