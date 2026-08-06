package com.viwa.android.data.local.recipe

import app.cash.turbine.test
import com.viwa.android.domain.customer.TelemetryCellsDefaultDosage
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CellEffectiveRecipeStoreTest {
    private lateinit var dao: FakeCellEffectiveRecipeDao
    private lateinit var store: CellEffectiveRecipeStore
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        dao = FakeCellEffectiveRecipeDao()
        nowMs = 1_000L
        store =
            CellEffectiveRecipeStore(
                dao = dao,
                featureEnabled = { true },
                clock = { nowMs },
            )
        store.setRuntimeManagedModeActive(true)
    }

    @Test
    fun `getEffective returns legacy template when feature off`() = runTest {
        val legacyStore =
            CellEffectiveRecipeStore(
                dao = dao,
                featureEnabled = { false },
                clock = { nowMs },
            )

        val recipe = legacyStore.getEffective("cell-1")

        assertNotNull(recipe)
        assertEquals(CellEffectiveRecipeDefaults.LEGACY_BASE_DRINK_VOLUME_ML, recipe!!.baseDrinkVolumeMl)
        assertEquals(CellEffectiveRecipeDefaults.LEGACY_WATER_DECI_ML, recipe.waterDeciMl)
        assertEquals(CellEffectiveRecipeDefaults.LEGACY_PRODUCT_DECI_ML, recipe.productDeciMl)
        assertEquals(CellEffectiveRecipeDefaults.legacyFingerprint, recipe.fingerprint)
        assertEquals(CellEffectiveRecipeSource.LEGACY_TEMPLATE, recipe.source)
        assertEquals(TelemetryCellsDefaultDosage.LOCAL_TEMPLATE_NOTE, recipe.sourceLabel)
        assertTrue(recipe.isRecipeComplete)
        assertEquals(0, dao.allRows().size)
    }

    @Test
    fun `getEffective returns null when managed mode on and no row exists`() = runTest {
        store.setRuntimeManagedModeActive(true)
        assertNull(store.getEffective("cell-missing"))
        assertEquals(0, dao.allRows().size)
    }

    @Test
    fun `advanceCancelWatermark persists control-only UNINITIALIZED row without recipe triple`() = runTest {
        assertEquals(12L, store.advanceCancelWatermark("cell-1", 12L))

        val row = dao.findByCellId("cell-1")
        assertNotNull(row)
        assertEquals(CellEffectiveRecipeSource.UNINITIALIZED.name, row!!.source)
        assertNull(row.baseDrinkVolumeMl)
        assertNull(row.waterDeciMl)
        assertNull(row.productDeciMl)
        assertNull(row.fingerprint)
        assertEquals(12L, row.cancelThroughGeneration)

        val read = store.getEffective("cell-1")
        assertNotNull(read)
        assertEquals(CellEffectiveRecipeSource.UNINITIALIZED, read!!.source)
        assertFalse(read.isRecipeComplete)
        assertNull(read.triple)
    }

    @Test
    fun `watermark first then first command apply initializes complete recipe`() = runTest {
        store.advanceCancelWatermark("cell-1", 5L)
        assertFalse(store.getEffective("cell-1")!!.isRecipeComplete)

        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val result = store.applyCommand(cellId = "cell-1", commandGeneration = 6L, triple = triple)

        assertTrue(result is CellEffectiveRecipeStore.ApplyCommandResult.Applied)
        val applied = (result as CellEffectiveRecipeStore.ApplyCommandResult.Applied).recipe
        assertTrue(applied.isRecipeComplete)
        assertEquals(CellEffectiveRecipeSource.COMMAND, applied.source)
        assertEquals(5L, applied.cancelThroughGeneration)
        assertEquals(6L, applied.lastAppliedCommandGeneration)
        assertNotNull(applied.fingerprint)
    }

    @Test
    fun `applyLocalEffectiveRecipe validates and recomputes fingerprint before persist`() = runTest {
        val triple =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2750,
                productDeciMl = 250,
            )
        val expectedFingerprint = RecipeCanonical.fingerprint(triple)

        val applied =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple = triple,
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
                productId = "prod-1",
                baseVersionId = "base-v2",
            )

        assertEquals(expectedFingerprint, applied.fingerprint)
        assertEquals(CellEffectiveRecipeSource.LOCAL_EDIT, applied.source)
        assertTrue(applied.isRecipeComplete)
        assertEquals("prod-1", applied.productId)
        assertEquals("base-v2", applied.baseVersionId)
        assertEquals(expectedFingerprint, dao.findByCellId("cell-1")!!.fingerprint)
    }

    @Test
    fun `applyCommand applies when generation exceeds watermark and lastApplied`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple

        val result =
            store.applyCommand(
                cellId = "cell-1",
                commandGeneration = 5L,
                triple = triple,
                productId = "prod-1",
                baseVersionId = "base-v1",
            )

        assertTrue(result is CellEffectiveRecipeStore.ApplyCommandResult.Applied)
        val applied = (result as CellEffectiveRecipeStore.ApplyCommandResult.Applied).recipe
        assertEquals(5L, applied.lastAppliedCommandGeneration)
        assertEquals(CellEffectiveRecipeSource.COMMAND, applied.source)
        assertTrue(applied.isRecipeComplete)
    }

    @Test
    fun `applyCommand rejects stale generation`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.applyCommand(cellId = "cell-1", commandGeneration = 10L, triple = triple)

        val stale =
            store.applyCommand(
                cellId = "cell-1",
                commandGeneration = 9L,
                triple =
                    RecipeCanonicalTriple(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2700,
                        productDeciMl = 300,
                    ),
            )

        assertTrue(stale is CellEffectiveRecipeStore.ApplyCommandResult.StaleGeneration)
        assertEquals(10L, dao.findByCellId("cell-1")!!.lastAppliedCommandGeneration)
    }

    @Test
    fun `applyCommand rejects duplicate generation`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.applyCommand(cellId = "cell-1", commandGeneration = 7L, triple = triple)

        val duplicate =
            store.applyCommand(cellId = "cell-1", commandGeneration = 7L, triple = triple)

        assertTrue(duplicate is CellEffectiveRecipeStore.ApplyCommandResult.StaleGeneration)
    }

    @Test
    fun `applyCommand rejects generation at or below cancel watermark on control-only row`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.advanceCancelWatermark("cell-1", cancelThroughGeneration = 12L)

        val cancelled =
            store.applyCommand(cellId = "cell-1", commandGeneration = 12L, triple = triple)

        assertTrue(cancelled is CellEffectiveRecipeStore.ApplyCommandResult.CancelledByWatermark)
        val row = dao.findByCellId("cell-1")
        assertNotNull(row)
        assertEquals(CellEffectiveRecipeSource.UNINITIALIZED.name, row!!.source)
        assertNull(row.fingerprint)
        assertEquals(0L, row.lastAppliedCommandGeneration)
        assertEquals(12L, row.cancelThroughGeneration)
    }

    @Test
    fun `applyCommand accepts generation above cancel watermark after watermark advance`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.advanceCancelWatermark("cell-1", cancelThroughGeneration = 12L)

        val applied =
            store.applyCommand(cellId = "cell-1", commandGeneration = 13L, triple = triple)

        assertTrue(applied is CellEffectiveRecipeStore.ApplyCommandResult.Applied)
        assertEquals(12L, (applied as CellEffectiveRecipeStore.ApplyCommandResult.Applied).recipe.cancelThroughGeneration)
    }

    @Test
    fun `advanceCancelWatermark is monotonic and never lowers`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.applyCommand(cellId = "cell-1", commandGeneration = 3L, triple = triple)

        assertEquals(20L, store.advanceCancelWatermark("cell-1", 20L))
        assertEquals(20L, store.advanceCancelWatermark("cell-1", 15L))
        assertEquals(25L, store.advanceCancelWatermark("cell-1", 25L))
        assertEquals(25L, dao.findByCellId("cell-1")!!.cancelThroughGeneration)
    }

    @Test
    fun `observeSnapshot emits persisted recipes`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple

        store.observeSnapshot().test {
            assertEquals(emptyList<com.viwa.android.domain.recipe.CellEffectiveRecipe>(), awaitItem())
            store.applyCommand(cellId = "cell-a", commandGeneration = 1L, triple = triple)
            val snapshot = awaitItem()
            assertEquals(1, snapshot.size)
            assertEquals("cell-a", snapshot.single().cellId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `out-of-order higher generation still applies after lower applied first`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.applyCommand(cellId = "cell-1", commandGeneration = 5L, triple = triple)

        val lateHigh =
            store.applyCommand(
                cellId = "cell-1",
                commandGeneration = 8L,
                triple =
                    RecipeCanonicalTriple(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2600,
                        productDeciMl = 400,
                    ),
            )

        assertTrue(lateHigh is CellEffectiveRecipeStore.ApplyCommandResult.Applied)
        assertEquals(400, dao.findByCellId("cell-1")!!.productDeciMl)
    }

    @Test
    fun `local edit preserves generation watermarks`() = runTest {
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        store.applyCommand(cellId = "cell-1", commandGeneration = 4L, triple = triple)
        store.advanceCancelWatermark("cell-1", 3L)

        val edited =
            store.applyLocalEffectiveRecipe(
                cellId = "cell-1",
                triple =
                    RecipeCanonicalTriple(
                        baseDrinkVolumeMl = 300,
                        waterDeciMl = 2650,
                        productDeciMl = 350,
                    ),
                source = CellEffectiveRecipeSource.LOCAL_EDIT,
            )

        assertEquals(4L, edited.lastAppliedCommandGeneration)
        assertEquals(3L, edited.cancelThroughGeneration)
        assertNotEquals(CellEffectiveRecipeSource.COMMAND, edited.source)
    }
}
