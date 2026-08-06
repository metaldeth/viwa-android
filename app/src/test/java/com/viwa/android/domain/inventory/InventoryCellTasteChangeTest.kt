package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCellTasteChangeTest {

    @Test
    fun `should preserve dosage prices and volumes when assigning product`() {
        // given
        val cell =
            TelemetryCell(
                uuid = "u1",
                cellNumber = 2,
                productUuid = "old-prod",
                productName = "Old",
                tasteMediaKey = "old",
                volume = 1500,
                blockVolume = 100,
                sosVolume = 300,
                maxVolume = 5000,
                dosage1Price = 9900,
                dosage2Price = 14900,
                conversionFactor = 6.25,
            )
        val product = TelemetryProduct(uuid = "new-prod", name = "New", tasteMediaKey = "new")

        // when
        val updated = InventoryCellTasteChange.applyProductAssignment(cell, product)

        // then
        assertEquals("new-prod", updated.productUuid)
        assertEquals("New", updated.productName)
        assertEquals("new", updated.tasteMediaKey)
        assertTrue(InventoryCellTasteChange.preservesPrices(cell, updated))
    }

    @Test
    fun `preservesPrices returns false when price changed`() {
        // given
        val before =
            TelemetryCell(
                uuid = "u1",
                cellNumber = 1,
                maxVolume = 5000,
                dosage1Price = 100,
            )
        val after = before.copy(dosage1Price = 200)

        // then
        assertTrue(!InventoryCellTasteChange.preservesPrices(before, after))
    }
}
