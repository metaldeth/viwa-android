package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.CellVolumeStatus
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.customer.TelemetryCellsDefaultDosage
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCellRecipeSupportTest {

    @Test
    fun `recipe basis matches shared default dosage template`() {
        val basis = InventoryCellRecipeSupport.recipeBasis(TelemetryCell.DEFAULT_CONVERSION_FACTOR)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_DRINK_VOLUME_ML, basis.drinkVolumeMl)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_WATER_ML, basis.waterMl, 0.01)
        assertEquals(TelemetryCellsDefaultDosage.RECIPE_PRODUCT_ML, basis.productMl, 0.01)
    }

    @Test
    fun `should scale water and product for 300 and 700 ml from Android basis`() {
        // given
        val basis = InventoryCellRecipeSupport.recipeBasis(conversionFactor = 4.0)

        // when
        val line300 = InventoryCellRecipeSupport.volumeRecipeLine(basis, 300)
        val line700 = InventoryCellRecipeSupport.volumeRecipeLine(basis, 700)

        // then
        assertEquals(270.0, line300.waterMl, 0.01)
        assertEquals(30.0, line300.productMl, 0.01)
        assertEquals(7.5, line300.dispenserSec!!, 0.01)
        assertEquals(630.0, line700.waterMl, 0.01)
        assertEquals(70.0, line700.productMl, 0.01)
        assertEquals(17.5, line700.dispenserSec!!, 0.01)
    }

    @Test
    fun `should mark recipe applicable only when product assigned`() {
        // given
        val withProduct = sampleRow(productUuid = "p1")
        val empty = sampleRow(productUuid = null)

        // then
        assertTrue(InventoryCellRecipeSupport.isRecipeApplicable(withProduct))
        assertFalse(InventoryCellRecipeSupport.isRecipeApplicable(empty))
    }

    @Test
    fun `volumeRecipeLine uses canonical integer scaling for 700 ml`() {
        // given — edge-round vector from architecture golden (2705/295 at 300 base)
        val triple =
            RecipeCanonicalTriple(
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2705,
                productDeciMl = 295,
            )

        // when
        val line =
            InventoryCellRecipeSupport.volumeRecipeLineFromTriple(
                baseTriple = triple,
                drinkVolumeMl = 700,
                conversionFactor = 4.0,
            )
        val expected = RecipeCanonical.scaleRecipeDeci(triple, 700).scaled!!

        // then
        assertNotNull(line)
        assertEquals(RecipeCanonical.deciMlToMl(expected.waterDeciMl), line!!.waterMl, 0.01)
        assertEquals(RecipeCanonical.deciMlToMl(expected.productDeciMl), line.productMl, 0.01)
    }

    @Test
    fun `formatVolumeLine includes drink volume and components`() {
        // given
        val basis = InventoryCellRecipeSupport.recipeBasis(5.0)
        val line = InventoryCellRecipeSupport.volumeRecipeLine(basis, 300)

        // when
        val text = InventoryCellRecipeSupport.formatVolumeLine(line)

        // then
        assertTrue(text.contains("300 мл"))
        assertTrue(text.contains("вода="))
        assertTrue(text.contains("сироп="))
    }

    private fun sampleRow(productUuid: String?): MvpInventoryTableRow =
        MvpInventoryTableRow(
            uuid = "cell-1",
            cellNumber = 1,
            productUuid = productUuid,
            productName = "Cola",
            tasteMediaKey = "cola",
            price300Kopecks = 9900,
            price700Kopecks = 14900,
            volumeMl = 1000,
            blockVolume = 0,
            sosVolume = 100,
            maxVolume = 5000,
            volumeStatus = CellVolumeStatus.NORMAL,
            conversionFactor = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
        )
}
