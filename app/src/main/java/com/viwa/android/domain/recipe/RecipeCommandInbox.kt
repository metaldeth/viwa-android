package com.viwa.android.domain.recipe

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.outbox.RecipeOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Ordered command inbox: sync.control watermark persisted before command processing;
 * commands sorted by (cellUuid, commandGeneration).
 */
@Singleton
class RecipeCommandInbox
@Inject
constructor(
    private val applier: RecipeCommandApplier,
    private val ackEmitter: RecipeCommandAckEmitter,
    private val recipeOutboxStore: RecipeOutboxStore,
    private val effectiveRecipeStore: CellEffectiveRecipeStore,
) {
    private val mutex = Mutex()
    private val pending = PriorityQueue<RecipeInboxEntry>(compareBy(::entrySortKey))
    private var watermarkPersisted = false

    suspend fun enqueueSyncControl(cells: List<RecipeSyncControlCell>) {
        mutex.withLock {
            pending.add(RecipeInboxEntry.SyncControl(cells))
        }
    }

    suspend fun enqueueCommand(command: RecipeCommandDownlink) {
        mutex.withLock {
            if (pending.size >= MAX_PENDING_ENTRIES) {
                Timber.w("RecipeCommandInbox: backpressure — dropping command ${command.commandId}")
                return
            }
            pending.add(RecipeInboxEntry.Command(command))
        }
    }

    /** Drain inbox in deterministic order; returns processed ack count. */
    suspend fun drain(): Int {
        var processed = 0
        while (true) {
            val next =
                mutex.withLock {
                    pending.poll()
                } ?: break
            when (next) {
                is RecipeInboxEntry.SyncControl -> {
                    next.cells.forEach { cell ->
                        applier.persistCancelWatermark(cell.cellUuid, cell.cancelThroughGeneration)
                    }
                    watermarkPersisted = true
                }
                is RecipeInboxEntry.Command -> {
                    if (!watermarkPersisted) {
                        Timber.w(
                            "RecipeCommandInbox: command ${next.command.commandId} before sync.control — requeue",
                        )
                        mutex.withLock { pending.add(next) }
                        break
                    }
                    val result = applier.apply(next.command)
                    val ack = applier.buildAck(result)
                    ackEmitter.emitAck(ack)
                    if (result is CellEffectiveRecipeStore.ManagedCommandApplyResult.Processed && result.recipeChanged) {
                        effectiveRecipeStore.getEffective(next.command.cellUuid)?.let { recipe ->
                            recipeOutboxStore.enqueueRecipeReport(recipe)
                        }
                    }
                    processed++
                }
            }
        }
        return processed
    }

    suspend fun resetTransientState() {
        mutex.withLock {
            pending.clear()
            watermarkPersisted = false
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    private fun entrySortKey(entry: RecipeInboxEntry): String =
        when (entry) {
            is RecipeInboxEntry.SyncControl -> "0"
            is RecipeInboxEntry.Command ->
                "1|${entry.command.cellUuid}|${entry.command.commandGeneration.toString().padStart(20, '0')}|${entry.command.commandId}"
        }

    companion object {
        const val MAX_PENDING_ENTRIES = 256
    }
}
