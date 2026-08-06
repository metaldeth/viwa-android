package com.viwa.android.data.local.outbox

import com.viwa.android.data.local.recipe.CellEffectiveRecipeDao
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_COMMAND_ACK
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_REPORT
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeReportCellUplink
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeTerminalAckRecovery
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/** Emitted when a durable `cells.recipe.report` row is acked/purged for [cellUuid]. */
data class RecipeReportDeliveryEvent(val cellUuid: String)

/**
 * Durable outbox for `cells.recipe.report` and `cells.recipe.command.ack`.
 * Persistence is independent of runtime managed-mode send gate; flush is coordinated separately.
 * `cells.recipe.sync.request` remains WS-only and is never stored here.
 */
@Singleton
class RecipeOutboxStore
@Inject
constructor(
    private val outboxStore: MachineOutboxStore,
    private val recipeCodec: RecipeMessageCodec,
    private val effectiveRecipeDao: CellEffectiveRecipeDao,
) {
    private val _reportDeliveryEvents = MutableSharedFlow<RecipeReportDeliveryEvent>(extraBufferCapacity = 16)
    val reportDeliveryEvents: SharedFlow<RecipeReportDeliveryEvent> = _reportDeliveryEvents.asSharedFlow()

    suspend fun enqueueRecipeReport(recipe: CellEffectiveRecipe): MachineOutboxStore.EnqueueResult {
        val triple = recipe.triple ?: return MachineOutboxStore.EnqueueResult.Duplicate(null)
        val fingerprint = recipe.fingerprint ?: RecipeCanonical.fingerprint(triple)
        recipeCodec.verifyOptionalFingerprint(triple, fingerprint)
        val idempotencyKey = reportIdempotencyKey(recipe.cellId, recipe.deviceReportRevision)
        val existing =
            outboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.CELLS_RECIPE_REPORT,
                idempotencyKey,
            )
        if (existing != null) {
            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)
        }
        val uplinkCell =
            RecipeReportCellUplink(
                cellUuid = recipe.cellId,
                effectiveRecipe = triple,
                effectiveFingerprint = fingerprint,
                lastAppliedCommandGeneration = recipe.lastAppliedCommandGeneration,
                cancelThroughGeneration = recipe.cancelThroughGeneration,
                deviceReportRevision = recipe.deviceReportRevision,
            )
        val payloadJson = recipeCodec.encodeReportPayload(listOf(uplinkCell))
        return enqueueRow(
            kind = MachineOutboxKind.CELLS_RECIPE_REPORT,
            idempotencyKey = idempotencyKey,
            payloadJson = payloadJson,
        )
    }

    suspend fun enqueueCommandAck(ack: RecipeCommandAckEntry): MachineOutboxStore.EnqueueResult {
        val idempotencyKey =
            commandAckIdempotencyKey(
                commandId = ack.commandId,
                status = ack.status,
                commandGeneration = ack.commandGeneration,
            )
        val existing =
            outboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK,
                idempotencyKey,
            )
        if (existing != null) {
            return MachineOutboxStore.EnqueueResult.Duplicate(existing.localId)
        }
        val payloadJson = recipeCodec.encodeCommandAckPayload(listOf(ack))
        return enqueueRow(
            kind = MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK,
            idempotencyKey = idempotencyKey,
            payloadJson = payloadJson,
        )
    }

    suspend fun enqueueCommandAcks(acks: List<RecipeCommandAckEntry>): Int {
        var enqueued = 0
        acks.forEach { ack ->
            when (enqueueCommandAck(ack)) {
                is MachineOutboxStore.EnqueueResult.Inserted -> enqueued++
                is MachineOutboxStore.EnqueueResult.Duplicate -> Unit
            }
        }
        return enqueued
    }

    /**
     * Startup / reconnect recovery: re-enqueue any persisted terminal ack not yet delivered upstream.
     * Does not depend on server command redelivery.
     */
    suspend fun recoverPendingTerminalAcks(): Int {
        var recovered = 0
        for (entity in effectiveRecipeDao.findUndeliveredTerminalAcks()) {
            val ack = RecipeTerminalAckRecovery.toAckEntry(entity) ?: continue
            when (enqueueCommandAck(ack)) {
                is MachineOutboxStore.EnqueueResult.Inserted -> recovered++
                is MachineOutboxStore.EnqueueResult.Duplicate -> Unit
            }
        }
        if (recovered > 0) {
            Timber.i("RecipeOutboxStore: recovered $recovered terminal ack(s) from Room")
        }
        return recovered
    }

    /**
     * After server accepts a command ack outbox row, mark Room terminal as delivered.
     * Idempotent: preserves commandId/status/generation for inbox dedup on server redelivery.
     */
    suspend fun onCommandAckOutboxDelivered(entry: MachineOutboxEntryEntity): Boolean {
        if (MachineOutboxKind.fromWire(entry.kind) != MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK) {
            return false
        }
        return markCommandAckDelivered(entry.idempotencyKey)
    }

    internal suspend fun markCommandAckDelivered(idempotencyKey: String): Boolean {
        val parts = idempotencyKey.split("|")
        if (parts.size != 3) return false
        val commandId = parts[0]
        val status = parts[1]
        val commandGeneration = parts[2].toLongOrNull() ?: return false
        val entity =
            effectiveRecipeDao.findUndeliveredTerminalAcks().firstOrNull {
                it.lastAppliedCommandId == commandId &&
                    it.lastTerminalAckStatus == status &&
                    it.lastTerminalCommandGeneration == commandGeneration
            } ?: return false
        val updated =
            effectiveRecipeDao.markTerminalAckDelivered(
                cellId = entity.cellId,
                commandId = commandId,
                status = status,
                commandGeneration = commandGeneration,
                updatedAtMs = System.currentTimeMillis(),
            )
        return updated > 0
    }

    suspend fun enqueueCompleteEffectiveReports(store: CellEffectiveRecipeStore): Int {
        var enqueued = 0
        for (recipe in store.listCompleteEffectiveForUplink()) {
            when (enqueueRecipeReport(recipe)) {
                is MachineOutboxStore.EnqueueResult.Inserted -> enqueued++
                is MachineOutboxStore.EnqueueResult.Duplicate -> Unit
            }
        }
        return enqueued
    }

    /** Queue durable report after local effective recipe edit (managed mode persistence always on). */
    suspend fun enqueueReportAfterLocalEdit(recipe: CellEffectiveRecipe): MachineOutboxStore.EnqueueResult =
        enqueueRecipeReport(recipe)

    suspend fun hasUnsentRecipeEntries(): Boolean = outboxStore.hasUnsentRecipeEntries()

    /** True when this cell has a pending/in-flight durable recipe report in outbox. */
    suspend fun hasUnsentReportForCell(cellUuid: String): Boolean =
        outboxStore.hasUnsentRecipeReportForCell(cellUuid)

    /**
     * After server accepts a recipe report outbox row, notify UI listeners for that cell.
     * Idempotent: safe on duplicate ack paths (WS + REST batch).
     */
    suspend fun onRecipeReportOutboxDelivered(entry: MachineOutboxEntryEntity): Boolean {
        if (MachineOutboxKind.fromWire(entry.kind) != MachineOutboxKind.CELLS_RECIPE_REPORT) {
            return false
        }
        val cellUuid = cellUuidFromReportIdempotencyKey(entry.idempotencyKey) ?: return false
        _reportDeliveryEvents.tryEmit(RecipeReportDeliveryEvent(cellUuid))
        return true
    }

    suspend fun countPendingRecipeEntries(): Int =
        outboxStore.listDrainable(limit = Int.MAX_VALUE).count { row ->
            MachineOutboxKind.fromWire(row.kind) in RECIPE_KINDS &&
                row.status in setOf(MachineOutboxStatus.PENDING.name, MachineOutboxStatus.IN_FLIGHT.name)
        }

    private suspend fun enqueueRow(
        kind: MachineOutboxKind,
        idempotencyKey: String,
        payloadJson: String,
    ): MachineOutboxStore.EnqueueResult {
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val row =
            MachineOutboxEntryEntity(
                localId = UUID.randomUUID().toString(),
                kind = kind.wireValue,
                idempotencyKey = idempotencyKey,
                messageId = messageId,
                payloadJson = payloadJson,
                status = MachineOutboxStatus.PENDING.name,
                attempts = 0,
                wsAckFailures = 0,
                nextRetryAtMs = now,
                lastError = null,
                sessionGenerationAtSend = null,
                createdAtMs = now,
                ackedAtMs = null,
                inFlightSinceMs = null,
            )
        return outboxStore.enqueueRaw(row)
    }

    companion object {
        private val RECIPE_KINDS =
            setOf(
                MachineOutboxKind.CELLS_RECIPE_REPORT,
                MachineOutboxKind.CELLS_RECIPE_COMMAND_ACK,
            )

        fun reportIdempotencyKey(cellId: String, deviceReportRevision: Long): String =
            "$cellId|$deviceReportRevision"

        fun cellUuidFromReportIdempotencyKey(idempotencyKey: String): String? {
            val separator = idempotencyKey.indexOf('|')
            if (separator <= 0) return null
            return idempotencyKey.substring(0, separator)
        }

        fun commandAckIdempotencyKey(
            commandId: String,
            status: String,
            commandGeneration: Long,
        ): String = "$commandId|$status|$commandGeneration"

        /** HTTP outbox contract: recipe kinds use messageId as transport idempotency key. */
        fun restIdempotencyKey(entry: MachineOutboxEntryEntity): String = entry.messageId
    }
}
