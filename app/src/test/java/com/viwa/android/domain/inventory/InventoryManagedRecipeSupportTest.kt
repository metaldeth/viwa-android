package com.viwa.android.domain.inventory

import com.viwa.android.domain.recipe.AssignmentStatus
import com.viwa.android.domain.recipe.CellAssignmentBase
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeDriftBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryManagedRecipeSupportTest {
    @Test
    fun `drift aligned when fingerprints match same product`() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val fp = CellEffectiveRecipeDefaults.legacyFingerprint
        val effective =
            CellEffectiveRecipe(
                cellId = "cell-1",
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl,
                fingerprint = fp,
                source = CellEffectiveRecipeSource.COMMAND,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 1L,
                cancelThroughGeneration = 0L,
                updatedAtMs = 0L,
            )
        val base =
            CellAssignmentBase(
                cellUuid = "cell-1",
                status = AssignmentStatus.ASSIGNED,
                productId = "prod-1",
                currentBaseVersionId = "base-1",
                baseRecipeRevision = 1,
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl,
                fingerprint = fp,
                receivedAtMs = 100L,
            )
        assertEquals(
            RecipeDriftBadge.ALIGNED,
            InventoryManagedRecipeSupport.computeDrift(effective, base, "prod-1", nowMs = 200L),
        )
    }

    @Test
    fun `drift modified when effective differs from base`() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val effective =
            CellEffectiveRecipe(
                cellId = "cell-1",
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl + 10,
                fingerprint = "0000000000000000000000000000000000000000000000000000000000000001",
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 1L,
                cancelThroughGeneration = 0L,
                updatedAtMs = 0L,
            )
        val base =
            CellAssignmentBase(
                cellUuid = "cell-1",
                status = AssignmentStatus.ASSIGNED,
                productId = "prod-1",
                currentBaseVersionId = "base-1",
                baseRecipeRevision = 1,
                baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                waterDeciMl = triple.waterDeciMl,
                productDeciMl = triple.productDeciMl,
                fingerprint = CellEffectiveRecipeDefaults.legacyFingerprint,
                receivedAtMs = 100L,
            )
        assertEquals(
            RecipeDriftBadge.MODIFIED,
            InventoryManagedRecipeSupport.computeDrift(effective, base, "prod-1", nowMs = 200L),
        )
    }

    @Test
    fun `unknown assignment never reports aligned`() {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val effective =
            CellEffectiveRecipe(
                cellId = "cell-1",
                baseDrinkVolumeMl = null,
                waterDeciMl = null,
                productDeciMl = null,
                fingerprint = null,
                source = CellEffectiveRecipeSource.UNINITIALIZED,
                productId = null,
                baseVersionId = null,
                lastAppliedCommandGeneration = 0L,
                cancelThroughGeneration = 0L,
                updatedAtMs = 0L,
            )
        val base =
            CellAssignmentBase(
                cellUuid = "cell-1",
                status = AssignmentStatus.UNKNOWN,
                productId = "prod-1",
                currentBaseVersionId = null,
                baseRecipeRevision = null,
                baseDrinkVolumeMl = null,
                waterDeciMl = null,
                productDeciMl = null,
                fingerprint = null,
                receivedAtMs = 100L,
            )
        assertEquals(
            RecipeDriftBadge.BASE_UNKNOWN,
            InventoryManagedRecipeSupport.computeDrift(effective, base, "prod-1", nowMs = 200L),
        )
    }

    @Test
    fun `edit draft rejects invalid sum invariant`() {
        val result =
            InventoryManagedRecipeSupport.validateEditDraft(
                InventoryManagedRecipeSupport.EditDraft("300", "2700", "301"),
            )
        assertFalse(result.valid)
    }

    @Test
    fun `edit draft accepts valid canonical triple`() {
        val result =
            InventoryManagedRecipeSupport.validateEditDraft(
                InventoryManagedRecipeSupport.EditDraft("300", "2700", "300"),
            )
        assertTrue(result.valid)
        assertEquals(300, result.triple!!.baseDrinkVolumeMl)
    }
}
