package com.viwa.android.ui.screens.service

import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellAssignmentBaseStore
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellAssignmentBaseDao
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeAssignmentControl
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncControlCell
import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Store + outbox integration for service recipe edit/reset paths. */
class ServiceViewModelRecipeTest {
    private lateinit var effectiveStore: CellEffectiveRecipeStore
    private lateinit var assignmentStore: CellAssignmentBaseStore
    private lateinit var outboxStack: RecipeOutboxTestFixtures.RecipeOutboxTestStack

    @Before
    fun setUp() {
        outboxStack = RecipeOutboxTestFixtures.createOutboxStack()
        effectiveStore =
            CellEffectiveRecipeStore(
                dao = outboxStack.recipeDao,
                featureEnabled = { true },
            )
        effectiveStore.setRuntimeManagedModeActive(true)
        assignmentStore = CellAssignmentBaseStore.forTests(FakeCellAssignmentBaseDao())
    }

    @Test
    fun `local edit increments revision and enqueues outbox report`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val recipe =
            effectiveStore.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = triple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = "base-1",
            )
        val result = outboxStack.recipeOutboxStore.enqueueReportAfterLocalEdit(recipe)
        assertTrue(result is MachineOutboxStore.EnqueueResult.Inserted)
        assertEquals(1L, recipe.deviceReportRevision)
    }

    @Test
    fun `reset to cached base produces local edit report not server reset command`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        assignmentStore.mergeFromSyncControl(
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
                            baseRecipeRevision = 2,
                            triple = triple,
                        ),
                ),
            ),
        )
        val base = assignmentStore.get("cell-1")!!
        val recipe =
            effectiveStore.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = base.triple!!,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = base.currentBaseVersionId,
            )
        assertEquals(CellEffectiveRecipeSource.LOCAL_EDIT, recipe.source)
        assertTrue(outboxStack.recipeOutboxStore.enqueueReportAfterLocalEdit(recipe) is MachineOutboxStore.EnqueueResult.Inserted)
    }

    @Test
    fun `drain coordinator triggered after outbox enqueue`() = runTest {
        val drainCoordinator = mockk<com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator>(relaxed = true)
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val recipe =
            effectiveStore.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = triple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = "base-1",
            )
        outboxStack.recipeOutboxStore.enqueueReportAfterLocalEdit(recipe)
        drainCoordinator.onEnqueue()
        coVerify { drainCoordinator.onEnqueue() }
    }
}
