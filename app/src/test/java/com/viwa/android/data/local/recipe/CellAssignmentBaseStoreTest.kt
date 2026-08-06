package com.viwa.android.data.local.recipe

import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeAssignmentControl
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.RecipeCanonical
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CellAssignmentBaseStoreTest {
    private lateinit var dao: FakeCellAssignmentBaseDao
    private lateinit var store: CellAssignmentBaseStore
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        dao = FakeCellAssignmentBaseDao()
        store = CellAssignmentBaseStore.forTests(dao = dao, clock = { nowMs })
    }

    @Test
    fun `partial merge upserts only provided cells`() = runTest {
        // given
        store.mergeFromSyncControl(
            listOf(
                assignedControl("cell-a", revision = 1),
            ),
        )
        nowMs += 100

        // when
        store.mergeFromSyncControl(
            listOf(
                assignedControl("cell-b", revision = 2),
            ),
        )

        // then
        assertEquals(1, store.get("cell-a")!!.baseRecipeRevision)
        assertEquals(2, store.get("cell-b")!!.baseRecipeRevision)
    }

    @Test
    fun `unassigned clears cached association`() = runTest {
        store.mergeFromSyncControl(listOf(assignedControl("cell-1", revision = 3)))
        store.mergeFromSyncControl(
            listOf(
                RecipeSyncControlCell(
                    cellUuid = "cell-1",
                    cancelThroughGeneration = 0L,
                    serverLastAppliedGeneration = 0L,
                    assignment =
                        RecipeAssignmentControl(status = AssignmentStatus.UNASSIGNED),
                ),
            ),
        )
        val row = store.get("cell-1")!!
        assertEquals(AssignmentStatus.UNASSIGNED, row.status)
        assertNull(row.productId)
        assertNull(row.fingerprint)
        assertNull(row.triple)
    }

    @Test
    fun `malformed assigned becomes unknown`() = runTest {
        store.mergeFromSyncControl(
            listOf(
                RecipeSyncControlCell(
                    cellUuid = "cell-1",
                    cancelThroughGeneration = 0L,
                    serverLastAppliedGeneration = 0L,
                    assignment =
                        RecipeAssignmentControl(
                            status = AssignmentStatus.ASSIGNED,
                            productId = "prod-1",
                            currentBaseVersionId = "base-1",
                            baseRecipeRevision = 1,
                            triple = null,
                        ),
                ),
            ),
        )
        assertEquals(AssignmentStatus.UNKNOWN, store.get("cell-1")!!.status)
    }

    @Test
    fun `assigned recomputes fingerprint`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.mergeFromSyncControl(
            listOf(
                RecipeSyncControlCell(
                    cellUuid = "cell-1",
                    cancelThroughGeneration = 0L,
                    serverLastAppliedGeneration = 0L,
                    assignment =
                        RecipeAssignmentControl(
                            status = AssignmentStatus.ASSIGNED,
                            productId = "prod-1",
                            currentBaseVersionId = "base-1",
                            baseRecipeRevision = 4,
                            triple = triple,
                            wireFingerprint = "0000000000000000000000000000000000000000000000000000000000000000",
                        ),
                ),
            ),
        )
        val row = store.get("cell-1")!!
        assertEquals(AssignmentStatus.ASSIGNED, row.status)
        assertEquals(RecipeCanonical.fingerprint(triple), row.fingerprint)
    }

    private fun assignedControl(cellUuid: String, revision: Int): RecipeSyncControlCell {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        return RecipeSyncControlCell(
            cellUuid = cellUuid,
            cancelThroughGeneration = 0L,
            serverLastAppliedGeneration = 0L,
            assignment =
                RecipeAssignmentControl(
                    status = AssignmentStatus.ASSIGNED,
                    productId = "prod-$cellUuid",
                    currentBaseVersionId = "base-$cellUuid",
                    baseRecipeRevision = revision,
                    triple = triple,
                ),
        )
    }
}
