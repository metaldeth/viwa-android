package com.viwa.android.data.local.recipe

import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeAssignmentControl
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellAssignmentBase
import com.viwa.android.domain.recipe.RecipeCanonical
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

@Singleton
class CellAssignmentBaseStore
private constructor(
    private val dao: CellAssignmentBaseDao,
    private val clock: () -> Long,
) {
    @Inject
    constructor(dao: CellAssignmentBaseDao) : this(dao, clock = { System.currentTimeMillis() })

    fun observeAll(): Flow<List<CellAssignmentBase>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun get(cellUuid: String): CellAssignmentBase? = dao.findByCellUuid(cellUuid)?.toDomain()

    /**
     * Merge assignment snapshots from sync.control — only cells present in [cells] are touched;
     * absent cells retain prior cached assignment.
     */
    suspend fun mergeFromSyncControl(cells: List<RecipeSyncControlCell>) {
        for (cell in cells) {
            val assignment = cell.assignment ?: continue
            mergeSingle(cell.cellUuid, assignment)
        }
    }

    internal suspend fun mergeSingle(cellUuid: String, assignment: RecipeAssignmentControl) {
        val now = clock()
        val existing = dao.findByCellUuid(cellUuid)
        val entity =
            when (assignment.status) {
                AssignmentStatus.UNASSIGNED ->
                    unassignedEntity(cellUuid, now, existing)
                AssignmentStatus.UNKNOWN ->
                    unknownEntity(cellUuid, assignment, now, existing)
                AssignmentStatus.ASSIGNED ->
                    assignedEntity(cellUuid, assignment, now, existing)
            }
        dao.upsert(entity)
    }

    private fun unassignedEntity(
        cellUuid: String,
        now: Long,
        existing: CellAssignmentBaseEntity?,
    ): CellAssignmentBaseEntity =
        CellAssignmentBaseEntity(
            cellUuid = cellUuid,
            status = AssignmentStatus.UNASSIGNED.name,
            productId = null,
            currentBaseVersionId = null,
            baseRecipeRevision = null,
            baseDrinkVolumeMl = null,
            waterDeciMl = null,
            productDeciMl = null,
            fingerprint = null,
            receivedAtMs = now,
            priorFingerprint = existing?.takeIf { it.status == AssignmentStatus.ASSIGNED.name }?.fingerprint,
            priorReceivedAtMs = existing?.takeIf { it.status == AssignmentStatus.ASSIGNED.name }?.receivedAtMs,
        )

    private fun unknownEntity(
        cellUuid: String,
        assignment: RecipeAssignmentControl,
        now: Long,
        existing: CellAssignmentBaseEntity?,
    ): CellAssignmentBaseEntity =
        CellAssignmentBaseEntity(
            cellUuid = cellUuid,
            status = AssignmentStatus.UNKNOWN.name,
            productId = assignment.productId,
            currentBaseVersionId = null,
            baseRecipeRevision = assignment.baseRecipeRevision,
            baseDrinkVolumeMl = null,
            waterDeciMl = null,
            productDeciMl = null,
            fingerprint = null,
            receivedAtMs = now,
            priorFingerprint = existing?.fingerprint,
            priorReceivedAtMs = existing?.receivedAtMs,
        )

    private fun assignedEntity(
        cellUuid: String,
        assignment: RecipeAssignmentControl,
        now: Long,
        existing: CellAssignmentBaseEntity?,
    ): CellAssignmentBaseEntity {
        val triple = assignment.triple
        if (
            triple == null ||
            assignment.productId.isNullOrBlank() ||
            assignment.currentBaseVersionId.isNullOrBlank() ||
            !RecipeCanonical.validate(triple).valid
        ) {
            Timber.w(
                "CellAssignmentBaseStore: malformed assigned shape for $cellUuid — storing UNKNOWN",
            )
            return unknownEntity(cellUuid, assignment.copy(status = AssignmentStatus.UNKNOWN), now, existing)
        }

        val recomputed = RecipeCanonical.fingerprint(triple)
        assignment.wireFingerprint?.let { wire ->
            if (wire.lowercase() != recomputed) {
                Timber.w(
                    "CellAssignmentBaseStore: fingerprint mismatch for $cellUuid expected=$recomputed got=$wire",
                )
            }
        }

        return CellAssignmentBaseEntity(
            cellUuid = cellUuid,
            status = AssignmentStatus.ASSIGNED.name,
            productId = assignment.productId,
            currentBaseVersionId = assignment.currentBaseVersionId,
            baseRecipeRevision = assignment.baseRecipeRevision,
            baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
            waterDeciMl = triple.waterDeciMl,
            productDeciMl = triple.productDeciMl,
            fingerprint = recomputed,
            receivedAtMs = now,
            priorFingerprint = null,
            priorReceivedAtMs = null,
        )
    }

    companion object {
        fun forTests(
            dao: CellAssignmentBaseDao,
            clock: () -> Long = { System.currentTimeMillis() },
        ): CellAssignmentBaseStore = CellAssignmentBaseStore(dao, clock)
    }
}
