package com.viwa.android.services.preparing

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.local.recipe.RecipeSyncFeatureFlags
import com.viwa.android.domain.customer.TelemetryCellsDefaultDosage
import com.viwa.android.domain.inventory.InventoryCellRecipeSupport
import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import com.viwa.android.domain.telemetry.DispenseTelemetryFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pour Phase C fail-safe resolution used by [PreparingManager] (task-19 / AND-6). */
class PreparingManagerRecipeTest {
    private lateinit var effectiveStore: CellEffectiveRecipeStore

    @Before
    fun setUp() {
        effectiveStore =
            CellEffectiveRecipeStore(
                dao = FakeCellEffectiveRecipeDao(),
                featureEnabled = { true },
            )
        effectiveStore.setRuntimeManagedModeActive(true)
    }

    @Test
    fun `should use customized effective base dosage when Phase C permitted`() = runTest {
        // given
        val customized =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2600,
                productDeciMl = 400,
            )
        persistEffective(customized)

        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = effectiveStore.getEffective("cell-1"),
                conversionFactor = 5.0,
                pourVolumeMl = 300,
                pourFromEffectivePermitted = true,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.EFFECTIVE, setup.source)
        assertNull(setup.fallbackReason)
        assertEquals(300, setup.baseDosage.drinkVolume)
        assertEquals(260.0, setup.baseDosage.water, 0.01)
        assertEquals(40.0, setup.baseDosage.product, 0.01)
    }

    @Test
    fun `should scale effective integers canonically for 700 ml pour without exception`() {
        // given
        val customized =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2600,
                productDeciMl = 400,
            )

        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = null,
                conversionFactor = 4.0,
                pourVolumeMl = 700,
                pourFromEffectivePermitted = false,
            )
        val scaled =
            InventoryCellRecipeSupport.dosageScaledToPourVolumeOrNull(
                baseTriple = customized,
                targetVolumeMl = 700,
                conversionFactor = 4.0,
            )
        val expectedLine =
            InventoryCellRecipeSupport.volumeRecipeLineFromTriple(
                baseTriple = customized,
                drinkVolumeMl = 700,
                conversionFactor = 4.0,
            )

        // then
        assertNotNull(scaled)
        assertNotNull(expectedLine)
        assertEquals(700, scaled!!.drinkVolume)
        assertEquals(expectedLine!!.waterMl, scaled.water, 0.01)
        assertEquals(expectedLine.productMl, scaled.product, 0.01)
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.LEGACY_TEMPLATE, setup.source)
    }

    @Test
    fun `should use legacy template when pour gate off even with managed effective`() = runTest {
        // given
        persistEffective(
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2500,
                productDeciMl = 500,
            ),
        )

        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = effectiveStore.getEffective("cell-1"),
                conversionFactor = TelemetryCellsDefaultDosage.template.conversionFactor,
                pourVolumeMl = 300,
                pourFromEffectivePermitted = false,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.LEGACY_TEMPLATE, setup.source)
        assertNull(setup.fallbackReason)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_WATER_ML, setup.baseDosage.water, 0.01)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_PRODUCT_ML, setup.baseDosage.product, 0.01)
    }

    @Test
    fun `should fallback with missing_effective reason when effective absent`() {
        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = null,
                conversionFactor = 4.0,
                pourVolumeMl = 300,
                pourFromEffectivePermitted = true,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.FALLBACK_LEGACY, setup.source)
        assertEquals(
            InventoryCellRecipeSupport.PourRecipeFallbackReason.MISSING_EFFECTIVE,
            setup.fallbackReason,
        )
        assertEquals("missing_effective", setup.fallbackReason?.wireValue)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML, setup.baseDosage.drinkVolume)
    }

    @Test
    fun `should fallback with incomplete_effective reason for uninitialized row`() {
        // given
        val incomplete =
            CellEffectiveRecipeDefaults.controlOnly(cellId = "cell-1")

        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = incomplete,
                conversionFactor = 4.0,
                pourVolumeMl = 700,
                pourFromEffectivePermitted = true,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.FALLBACK_LEGACY, setup.source)
        assertEquals(
            InventoryCellRecipeSupport.PourRecipeFallbackReason.INCOMPLETE_EFFECTIVE,
            setup.fallbackReason,
        )
        assertTrue(setup.diagnostics!!.contains("UNINITIALIZED"))
    }

    @Test
    fun `should fallback with invalid_effective reason when triple fails validation`() {
        // given — sum invariant broken but non-null fields (corrupt persist)
        val corrupt =
            CellEffectiveRecipe(
                cellId = "cell-1",
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2700,
                productDeciMl = 400,
                fingerprint = "deadbeef",
                source = CellEffectiveRecipeSource.COMMAND,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 1L,
                cancelThroughGeneration = 0L,
                updatedAtMs = 0L,
            )

        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = corrupt,
                conversionFactor = 4.0,
                pourVolumeMl = 300,
                pourFromEffectivePermitted = true,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.FALLBACK_LEGACY, setup.source)
        assertEquals(
            InventoryCellRecipeSupport.PourRecipeFallbackReason.INVALID_EFFECTIVE,
            setup.fallbackReason,
        )
        assertTrue(setup.diagnostics!!.contains("validationErrors"))
    }

    @Test
    fun `should fallback with scale_failed reason when target volume out of range`() = runTest {
        // given
        persistEffective(
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2600,
                productDeciMl = 400,
            ),
        )

        // when — 1500 ml exceeds RecipeCanonical.BASE_DRINK_VOLUME_ML_MAX (1000)
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = effectiveStore.getEffective("cell-1"),
                conversionFactor = 4.0,
                pourVolumeMl = 1500,
                pourFromEffectivePermitted = true,
            )

        // then
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.FALLBACK_LEGACY, setup.source)
        assertEquals(
            InventoryCellRecipeSupport.PourRecipeFallbackReason.SCALE_FAILED,
            setup.fallbackReason,
        )
        assertTrue(setup.diagnostics!!.contains("scaleErrors"))
        assertNull(
            InventoryCellRecipeSupport.dosageScaledToPourVolumeOrNull(
                baseTriple =
                    RecipeCanonicalTriple(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2600,
                        productDeciMl = 400,
                    ),
                targetVolumeMl = 1500,
                conversionFactor = 4.0,
            ),
        )
    }

    @Test
    fun `paid pour policy continues on fallback using legacy controller dosage`() {
        // when
        val setup =
            InventoryCellRecipeSupport.resolvePourSetup(
                effective = null,
                conversionFactor = 5.0,
                pourVolumeMl = 700,
                pourFromEffectivePermitted = true,
            )

        // then — documented policy: no fail-closed crash; legacy template drives controller
        assertEquals(InventoryCellRecipeSupport.PourRecipeSource.FALLBACK_LEGACY, setup.source)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML, setup.controllerDosage.drinkVolume)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_WATER_ML, setup.controllerDosage.water, 0.01)
    }

    @Test
    fun `pour gate requires feature sync managed and explicit pour flag`() {
        assertFalse(
            InventoryCellRecipeSupport.isPourFromEffectivePermitted(
                managedGateActive = true,
                featureEnabled = false,
                pourGateEnabled = true,
            ),
        )
        assertFalse(
            InventoryCellRecipeSupport.isPourFromEffectivePermitted(
                managedGateActive = false,
                featureEnabled = true,
                pourGateEnabled = true,
            ),
        )
        assertFalse(
            InventoryCellRecipeSupport.isPourFromEffectivePermitted(
                managedGateActive = true,
                featureEnabled = true,
                pourGateEnabled = false,
            ),
        )
        assertTrue(
            InventoryCellRecipeSupport.isPourFromEffectivePermitted(
                managedGateActive = true,
                featureEnabled = true,
                pourGateEnabled = true,
            ),
        )
        // Phase C prod rollout: both compile-time gates are on; pour still needs managed gate at runtime.
        assertTrue(RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC)
        assertTrue(RecipeSyncFeatureFlags.FEATURE_RECIPE_POUR_FROM_EFFECTIVE)
    }

    @Test
    fun `telemetry snapshot carries effective base ml fields from resolved dosage`() {
        // given
        val baseDosage =
            DrinkDosage(
                conversionFactor = 5.5,
                drinkVolume = 300,
                water = 260.0,
                product = 40.0,
            )

        // when
        val paid =
            DispenseTelemetryFactory.paidComplete(
                transactionId = "tx-1",
                requestUuid = "req-1",
                volumeMl = 700,
                amountRub = 150.0,
                payMethod = "CARD",
                productId = "prod-1",
                productNameSnapshot = "Cola",
                concentration = DrinkConcentration.Standard,
                dosage = baseDosage,
            )

        // then
        assertEquals(300, paid.recipeDrinkVolumeMl)
        assertEquals(260.0, paid.recipeWaterMl!!, 0.01)
        assertEquals(40.0, paid.recipeProductMl!!, 0.01)
        assertEquals(5.5, paid.conversionFactor!!, 0.01)
    }

    private suspend fun persistEffective(triple: RecipeCanonicalTriple) {
        effectiveStore.applyLocalEffectiveRecipe(
            cellId = "cell-1",
            triple = triple,
            source = CellEffectiveRecipeSource.LOCAL_EDIT,
            productId = "prod-1",
            baseVersionId = "base-1",
        )
    }
}
