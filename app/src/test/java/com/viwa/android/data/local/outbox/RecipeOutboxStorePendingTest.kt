package com.viwa.android.data.local.outbox

import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeOutboxStorePendingTest {
    private lateinit var stack: RecipeOutboxTestFixtures.RecipeOutboxTestStack

    @Before
    fun setUp() {
        stack = RecipeOutboxTestFixtures.createOutboxStack()
    }

    @Test
    fun `hasUnsentReportForCell is per cell not global`() = runTest {
        // given
        enqueueReport("cell-a", revision = 1L)
        enqueueReport("cell-b", revision = 1L)

        // then
        assertTrue(stack.recipeOutboxStore.hasUnsentReportForCell("cell-a"))
        assertTrue(stack.recipeOutboxStore.hasUnsentReportForCell("cell-b"))
        assertFalse(stack.recipeOutboxStore.hasUnsentReportForCell("cell-c"))
    }

    @Test
    fun `onRecipeReportOutboxDelivered clears pending for that cell only`() = runTest {
        // given
        enqueueReport("cell-a", revision = 1L)
        enqueueReport("cell-b", revision = 1L)
        val cellAEntry =
            stack.machineOutboxStore.findByKindAndIdempotencyKey(
                MachineOutboxKind.CELLS_RECIPE_REPORT,
                RecipeOutboxStore.reportIdempotencyKey("cell-a", 1L),
            )!!

        // when
        stack.machineOutboxStore.markAcked(
            messageId = cellAEntry.messageId,
            kind = MachineOutboxKind.CELLS_RECIPE_REPORT,
        )
        stack.recipeOutboxStore.onRecipeReportOutboxDelivered(cellAEntry)

        // then
        assertFalse(stack.recipeOutboxStore.hasUnsentReportForCell("cell-a"))
        assertTrue(stack.recipeOutboxStore.hasUnsentReportForCell("cell-b"))
    }

    @Test
    fun `cellUuidFromReportIdempotencyKey parses prefix before pipe`() {
        assertEquals(
            "cell-1",
            RecipeOutboxStore.cellUuidFromReportIdempotencyKey("cell-1|42"),
        )
        assertEquals(null, RecipeOutboxStore.cellUuidFromReportIdempotencyKey("invalid"))
    }

    private suspend fun enqueueReport(cellId: String, revision: Long) {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val recipe =
            CellEffectiveRecipe(
                cellId = cellId,
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl,
                fingerprint = CellEffectiveRecipeDefaults.legacyFingerprint,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 0L,
                cancelThroughGeneration = 0L,
                deviceReportRevision = revision,
                updatedAtMs = 0L,
            )
        stack.recipeOutboxStore.enqueueReportAfterLocalEdit(recipe)
    }
}
