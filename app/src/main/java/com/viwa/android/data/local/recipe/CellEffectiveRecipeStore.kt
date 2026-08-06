package com.viwa.android.data.local.recipe

import androidx.room.withTransaction
import com.viwa.android.data.local.db.ViwaDatabase
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_BEARING_COMMAND_KINDS
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_UNASSIGN_CLEAR_KIND
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandAckEntry
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeCommandDownlink
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import com.viwa.android.domain.recipe.RecipeCommandAckStatus
import com.viwa.android.domain.recipe.RecipeTerminalAckRecovery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class CellEffectiveRecipeStore
private constructor(
    private val databaseOrNull: ViwaDatabase?,
    private val dao: CellEffectiveRecipeDao,
    private val featureEnabled: () -> Boolean,
    private val passthroughTransactions: Boolean,
    private val clock: () -> Long,
) {
    @Volatile
    private var runtimeManagedModeActive: Boolean = false

    @Inject
    constructor(
        database: ViwaDatabase,
        dao: CellEffectiveRecipeDao,
    ) : this(
        databaseOrNull = database,
        dao = dao,
        featureEnabled = { RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC },
        passthroughTransactions = false,
        clock = { System.currentTimeMillis() },
    )

    /** Passthrough transactions for unit tests — avoids MockK deadlock on [RoomDatabase.withTransaction]. */
    internal constructor(
        dao: CellEffectiveRecipeDao,
        featureEnabled: () -> Boolean = { RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC },
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(
        databaseOrNull = null,
        dao = dao,
        featureEnabled = featureEnabled,
        passthroughTransactions = true,
        clock = clock,
    )

    fun setRuntimeManagedModeActive(active: Boolean) {
        runtimeManagedModeActive = active
    }

    fun isRuntimeManagedModeActive(): Boolean = featureEnabled() && runtimeManagedModeActive

    fun observeSnapshot(): Flow<List<CellEffectiveRecipe>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Feature off → labeled legacy template (always complete).
     * Feature on but runtime gate inactive → null (unknown / not initialized).
     * Runtime managed → persisted row or null when no row exists.
     */
    suspend fun getEffective(cellId: String): CellEffectiveRecipe? {
        if (!featureEnabled()) {
            return CellEffectiveRecipeDefaults.legacyForCell(cellId, clock())
        }
        if (!runtimeManagedModeActive) {
            return null
        }
        return dao.findByCellId(cellId)?.toDomain()
    }

    suspend fun hasPersistedRow(cellId: String): Boolean = dao.findByCellId(cellId) != null

    /** Complete effective rows for reconnect uplink (excludes UNINITIALIZED control-only). */
    suspend fun listCompleteEffectiveForUplink(): List<CellEffectiveRecipe> {
        if (!featureEnabled()) return emptyList()
        return dao.findAll().map { it.toDomain() }.filter { it.isRecipeComplete }
    }

    suspend fun peekDeviceReportRevision(cellId: String): Long =
        dao.findByCellId(cellId)?.deviceReportRevision ?: 0L

    suspend fun nextDeviceReportRevision(cellId: String): Long =
        inTransaction {
            val current = dao.findByCellId(cellId)
            val previous = current?.deviceReportRevision ?: 0L
            val next = previous + 1L
            val now = clock()
            if (current == null) {
                dao.upsert(
                    CellEffectiveRecipeEntity.controlOnly(
                        cellId = cellId,
                        cancelThroughGeneration = 0L,
                        updatedAtMs = now,
                    ).copy(deviceReportRevision = next),
                )
            } else {
                dao.upsert(current.copy(deviceReportRevision = next, updatedAtMs = now))
            }
            next
        }

    suspend fun applyLocalEffectiveRecipe(
        cellId: String,
        triple: RecipeCanonicalTriple,
        source: CellEffectiveRecipeSource,
        productId: String? = null,
        baseVersionId: String? = null,
    ): CellEffectiveRecipe {
        require(source != CellEffectiveRecipeSource.LEGACY_TEMPLATE) {
            "LEGACY_TEMPLATE is reserved for feature-off fallback"
        }
        require(source != CellEffectiveRecipeSource.UNINITIALIZED) {
            "UNINITIALIZED is reserved for control-only rows"
        }
        require(featureEnabled()) { "Managed recipe persistence requires FEATURE_RECIPE_SYNC" }

        val validated = RecipeCanonical.assertValid(triple)
        val fingerprint = RecipeCanonical.fingerprint(validated)
        val now = clock()

        return inTransaction {
            val current = dao.findByCellId(cellId)
            val nextRevision = (current?.deviceReportRevision ?: 0L) + 1L
            val recipe =
                CellEffectiveRecipe(
                    cellId = cellId,
                    baseDrinkVolumeMl = validated.baseDrinkVolumeMl,
                    waterDeciMl = validated.waterDeciMl,
                    productDeciMl = validated.productDeciMl,
                    fingerprint = fingerprint,
                    source = source,
                    productId = productId,
                    baseVersionId = baseVersionId,
                    lastAppliedCommandGeneration = current?.lastAppliedCommandGeneration ?: 0L,
                    cancelThroughGeneration = current?.cancelThroughGeneration ?: 0L,
                    deviceReportRevision = nextRevision,
                    lastAppliedCommandId = current?.lastAppliedCommandId,
                    lastTerminalAckStatus = current?.lastTerminalAckStatus,
                    lastTerminalCommandGeneration = current?.lastTerminalCommandGeneration ?: 0L,
                    lastTerminalAckFailureCode = current?.lastTerminalAckFailureCode,
                    terminalAckDelivered = current?.terminalAckDelivered ?: false,
                    updatedAtMs = now,
                )
            dao.upsert(CellEffectiveRecipeEntity.fromDomain(recipe))
            recipe
        }
    }

    suspend fun applyCommand(
        cellId: String,
        commandGeneration: Long,
        triple: RecipeCanonicalTriple,
        productId: String? = null,
        baseVersionId: String? = null,
    ): ApplyCommandResult {
        require(featureEnabled()) { "Managed recipe commands require FEATURE_RECIPE_SYNC" }
        require(commandGeneration > 0L) { "commandGeneration must be positive" }

        val validated = RecipeCanonical.assertValid(triple)
        val fingerprint = RecipeCanonical.fingerprint(validated)
        val now = clock()

        return inTransaction {
            val current = dao.findByCellId(cellId)
            val cancelWatermark = current?.cancelThroughGeneration ?: 0L
            val lastApplied = current?.lastAppliedCommandGeneration ?: 0L

            when {
                commandGeneration <= cancelWatermark ->
                    ApplyCommandResult.CancelledByWatermark(
                        commandGeneration = commandGeneration,
                        cancelThroughGeneration = cancelWatermark,
                    )
                commandGeneration <= lastApplied ->
                    ApplyCommandResult.StaleGeneration(
                        commandGeneration = commandGeneration,
                        lastAppliedCommandGeneration = lastApplied,
                    )
                else -> {
                    val nextRevision = (current?.deviceReportRevision ?: 0L) + 1L
                    val recipe =
                        CellEffectiveRecipe(
                            cellId = cellId,
                            baseDrinkVolumeMl = validated.baseDrinkVolumeMl,
                            waterDeciMl = validated.waterDeciMl,
                            productDeciMl = validated.productDeciMl,
                            fingerprint = fingerprint,
                            source = CellEffectiveRecipeSource.COMMAND,
                            productId = productId,
                            baseVersionId = baseVersionId,
                            lastAppliedCommandGeneration = commandGeneration,
                            cancelThroughGeneration = cancelWatermark,
                            deviceReportRevision = nextRevision,
                            updatedAtMs = now,
                        )
                    dao.upsert(CellEffectiveRecipeEntity.fromDomain(recipe))
                    ApplyCommandResult.Applied(recipe)
                }
            }
        }
    }

    /**
     * Atomic managed command apply with terminal ack persistence (task-16 / AC-20).
     * Crash before ack: redelivery by same commandId re-emits identical terminal ack without reapply.
     */
    suspend fun applyManagedCommand(command: RecipeCommandDownlink): ManagedCommandApplyResult {
        require(featureEnabled()) { "Managed recipe commands require FEATURE_RECIPE_SYNC" }

        return inTransaction {
            val current = dao.findByCellId(command.cellUuid)
            val cancelWatermark = current?.cancelThroughGeneration ?: 0L
            val lastApplied = current?.lastAppliedCommandGeneration ?: 0L

            if (
                current?.lastAppliedCommandId == command.commandId &&
                !current.lastTerminalAckStatus.isNullOrBlank()
            ) {
                return@inTransaction ManagedCommandApplyResult.Redelivered(
                    RecipeTerminalAckRecovery.toAckEntry(current)!!,
                )
            }

            when {
                command.commandGeneration <= cancelWatermark -> {
                    persistTerminalOutcome(
                        current = current,
                        command = command,
                        status = RecipeCommandAckStatus.CANCELLED,
                        bumpRevision = false,
                        appliedRecipe = null,
                    )
                }
                command.commandGeneration <= lastApplied -> {
                    persistTerminalOutcome(
                        current = current,
                        command = command,
                        status = RecipeCommandAckStatus.SUPERSEDED,
                        bumpRevision = false,
                        appliedRecipe = null,
                    )
                }
                else -> applyManagedCommandBody(current, command)
            }
        }
    }

    /**
     * Monotonic per-cell cancel watermark (architecture §5.4 / C-A3).
     * Persists control state only — never fabricates an effective recipe triple.
     */
    suspend fun advanceCancelWatermark(cellId: String, cancelThroughGeneration: Long): Long {
        require(featureEnabled()) { "Managed recipe watermark requires FEATURE_RECIPE_SYNC" }
        require(cancelThroughGeneration >= 0L) { "cancelThroughGeneration must be non-negative" }

        return inTransaction {
            val current = dao.findByCellId(cellId)
            val previous = current?.cancelThroughGeneration ?: 0L
            val next = maxOf(previous, cancelThroughGeneration)
            if (current == null && next == 0L) {
                return@inTransaction 0L
            }
            if (current != null && next == previous) {
                return@inTransaction previous
            }
            val now = clock()
            if (current == null || current.source == CellEffectiveRecipeSource.UNINITIALIZED.name) {
                dao.upsert(
                    CellEffectiveRecipeEntity.controlOnly(
                        cellId = cellId,
                        cancelThroughGeneration = next,
                        lastAppliedCommandGeneration = current?.lastAppliedCommandGeneration ?: 0L,
                        updatedAtMs = now,
                    ).copy(deviceReportRevision = current?.deviceReportRevision ?: 0L),
                )
            } else {
                val domain = current.toDomain()
                dao.upsert(
                    CellEffectiveRecipeEntity.fromDomain(
                        domain.copy(
                            cancelThroughGeneration = next,
                            updatedAtMs = now,
                        ),
                    ),
                )
            }
            next
        }
    }

    sealed class ApplyCommandResult {
        data class Applied(val recipe: CellEffectiveRecipe) : ApplyCommandResult()

        data class StaleGeneration(
            val commandGeneration: Long,
            val lastAppliedCommandGeneration: Long,
        ) : ApplyCommandResult()

        data class CancelledByWatermark(
            val commandGeneration: Long,
            val cancelThroughGeneration: Long,
        ) : ApplyCommandResult()
    }

    sealed class ManagedCommandApplyResult {
        data class Processed(val ack: RecipeCommandAckEntry, val recipeChanged: Boolean) :
            ManagedCommandApplyResult()

        data class Redelivered(val ack: RecipeCommandAckEntry) : ManagedCommandApplyResult()
    }

    private suspend fun applyManagedCommandBody(
        current: CellEffectiveRecipeEntity?,
        command: RecipeCommandDownlink,
    ): ManagedCommandApplyResult =
        when (command.kind) {
            RECIPE_UNASSIGN_CLEAR_KIND -> {
                persistTerminalOutcome(
                    current = current,
                    command = command,
                    status = RecipeCommandAckStatus.APPLIED,
                    bumpRevision = true,
                    appliedRecipe = null,
                    clearEffective = true,
                )
            }
            in FORCE_APPLY_KINDS -> applyForceRecipe(current, command)
            CAMPAIGN_CONDITIONAL_KIND -> applyConditionalRecipe(current, command)
            else ->
                persistTerminalOutcome(
                    current = current,
                    command = command,
                    status = RecipeCommandAckStatus.FAILED,
                    bumpRevision = false,
                    appliedRecipe = null,
                    failureCode = "UNSUPPORTED_KIND",
                )
        }

    private suspend fun applyForceRecipe(
        current: CellEffectiveRecipeEntity?,
        command: RecipeCommandDownlink,
    ): ManagedCommandApplyResult {
        val triple = command.targetRecipe
        if (triple == null || command.targetBaseVersionId.isNullOrBlank()) {
            return persistTerminalOutcome(
                current = current,
                command = command,
                status = RecipeCommandAckStatus.FAILED,
                bumpRevision = false,
                appliedRecipe = null,
                failureCode = "INVALID_TARGET",
            )
        }
        return persistRecipeApply(
            current = current,
            command = command,
            triple = triple,
            productId = null,
            baseVersionId = command.targetBaseVersionId,
            status = RecipeCommandAckStatus.APPLIED,
        )
    }

    private suspend fun applyConditionalRecipe(
        current: CellEffectiveRecipeEntity?,
        command: RecipeCommandDownlink,
    ): ManagedCommandApplyResult {
        val triple = command.targetRecipe
        val expectedFingerprint = command.fromFingerprint?.lowercase()
        if (
            triple == null ||
            command.targetBaseVersionId.isNullOrBlank() ||
            expectedFingerprint.isNullOrBlank() ||
            expectedFingerprint.length != 64
        ) {
            return persistTerminalOutcome(
                current = current,
                command = command,
                status = RecipeCommandAckStatus.FAILED,
                bumpRevision = false,
                appliedRecipe = null,
                failureCode = "INVALID_TARGET",
            )
        }
        val domain = current?.toDomain()
        val actualFingerprint = domain?.takeIf { it.isRecipeComplete }?.fingerprint?.lowercase()
        if (actualFingerprint == null || actualFingerprint != expectedFingerprint) {
            return persistTerminalOutcome(
                current = current,
                command = command,
                status = RecipeCommandAckStatus.SKIPPED_DIVERGED,
                bumpRevision = false,
                appliedRecipe = null,
            )
        }
        return persistRecipeApply(
            current = current,
            command = command,
            triple = triple,
            productId = null,
            baseVersionId = command.targetBaseVersionId,
            status = RecipeCommandAckStatus.APPLIED,
        )
    }

    private suspend fun persistRecipeApply(
        current: CellEffectiveRecipeEntity?,
        command: RecipeCommandDownlink,
        triple: RecipeCanonicalTriple,
        productId: String?,
        baseVersionId: String?,
        status: String,
    ): ManagedCommandApplyResult {
        val validated = runCatching { RecipeCanonical.assertValid(triple) }.getOrElse {
            return persistTerminalOutcome(
                current = current,
                command = command,
                status = RecipeCommandAckStatus.FAILED,
                bumpRevision = false,
                appliedRecipe = null,
                failureCode = "INVALID_TARGET",
            )
        }
        val fingerprint = RecipeCanonical.fingerprint(validated)
        val now = clock()
        val nextRevision = (current?.deviceReportRevision ?: 0L) + 1L
        val recipe =
            CellEffectiveRecipe(
                cellId = command.cellUuid,
                baseDrinkVolumeMl = validated.baseDrinkVolumeMl,
                waterDeciMl = validated.waterDeciMl,
                productDeciMl = validated.productDeciMl,
                fingerprint = fingerprint,
                source = CellEffectiveRecipeSource.COMMAND,
                productId = productId,
                baseVersionId = baseVersionId,
                lastAppliedCommandGeneration = command.commandGeneration,
                cancelThroughGeneration = current?.cancelThroughGeneration ?: 0L,
                deviceReportRevision = nextRevision,
                lastAppliedCommandId = command.commandId,
                lastTerminalAckStatus = status,
                lastTerminalCommandGeneration = command.commandGeneration,
                lastTerminalAckFailureCode = null,
                terminalAckDelivered = false,
                updatedAtMs = now,
            )
        dao.upsert(CellEffectiveRecipeEntity.fromDomain(recipe))
        return ManagedCommandApplyResult.Processed(
            ack =
                buildAckEntry(
                    command = command,
                    status = status,
                    appliedRecipe = validated,
                    failureCode = null,
                ),
            recipeChanged = true,
        )
    }

    private suspend fun persistTerminalOutcome(
        current: CellEffectiveRecipeEntity?,
        command: RecipeCommandDownlink,
        status: String,
        bumpRevision: Boolean,
        appliedRecipe: RecipeCanonicalTriple?,
        failureCode: String? = null,
        clearEffective: Boolean = false,
    ): ManagedCommandApplyResult {
        val now = clock()
        val cancelWatermark = current?.cancelThroughGeneration ?: 0L
        val nextRevision =
            if (bumpRevision) {
                (current?.deviceReportRevision ?: 0L) + 1L
            } else {
                current?.deviceReportRevision ?: 0L
            }
        val recipe =
            if (clearEffective) {
                CellEffectiveRecipe(
                    cellId = command.cellUuid,
                    baseDrinkVolumeMl = null,
                    waterDeciMl = null,
                    productDeciMl = null,
                    fingerprint = null,
                    source = CellEffectiveRecipeSource.UNINITIALIZED,
                    productId = null,
                    baseVersionId = null,
                    lastAppliedCommandGeneration = command.commandGeneration,
                    cancelThroughGeneration = cancelWatermark,
                    deviceReportRevision = nextRevision,
                    lastAppliedCommandId = command.commandId,
                    lastTerminalAckStatus = status,
                    lastTerminalCommandGeneration = command.commandGeneration,
                    lastTerminalAckFailureCode = null,
                    terminalAckDelivered = false,
                    updatedAtMs = now,
                )
            } else {
                val domain = current?.toDomain()
                val nextLastApplied =
                    if (status == RecipeCommandAckStatus.APPLIED) {
                        command.commandGeneration
                    } else {
                        domain?.lastAppliedCommandGeneration ?: 0L
                    }
                CellEffectiveRecipe(
                    cellId = command.cellUuid,
                    baseDrinkVolumeMl = domain?.baseDrinkVolumeMl,
                    waterDeciMl = domain?.waterDeciMl,
                    productDeciMl = domain?.productDeciMl,
                    fingerprint = domain?.fingerprint,
                    source = domain?.source ?: CellEffectiveRecipeSource.UNINITIALIZED,
                    productId = domain?.productId,
                    baseVersionId = domain?.baseVersionId,
                    lastAppliedCommandGeneration = nextLastApplied,
                    cancelThroughGeneration = cancelWatermark,
                    deviceReportRevision = nextRevision,
                    lastAppliedCommandId = command.commandId,
                    lastTerminalAckStatus = status,
                    lastTerminalCommandGeneration = command.commandGeneration,
                    lastTerminalAckFailureCode = failureCode,
                    terminalAckDelivered = false,
                    updatedAtMs = now,
                )
            }
        dao.upsert(CellEffectiveRecipeEntity.fromDomain(recipe))
        return ManagedCommandApplyResult.Processed(
            ack =
                buildAckEntry(
                    command = command,
                    status = status,
                    appliedRecipe = appliedRecipe,
                    failureCode = failureCode,
                ),
            recipeChanged = bumpRevision || clearEffective,
        )
    }

    private fun buildAckEntry(
        command: RecipeCommandDownlink,
        status: String,
        appliedRecipe: RecipeCanonicalTriple?,
        failureCode: String?,
    ): RecipeCommandAckEntry =
        RecipeCommandAckEntry(
            commandId = command.commandId,
            commandGeneration = command.commandGeneration,
            cellUuid = command.cellUuid,
            status = status,
            failureCode = failureCode,
            appliedRecipe = appliedRecipe,
        )

    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        if (passthroughTransactions) {
            block()
        } else {
            databaseOrNull!!.withTransaction { block() }
        }

    companion object {
        private const val CAMPAIGN_CONDITIONAL_KIND = "CAMPAIGN_CONDITIONAL_APPLY"

        private val FORCE_APPLY_KINDS =
            RECIPE_BEARING_COMMAND_KINDS -
                setOf(CAMPAIGN_CONDITIONAL_KIND)
    }
}
