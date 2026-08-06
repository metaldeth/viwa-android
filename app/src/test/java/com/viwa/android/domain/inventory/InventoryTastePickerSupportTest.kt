package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.TelemetryProduct
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTastePickerSupportTest {

    @Test
    fun `marks current product and disables selection semantics`() {
        val product = TelemetryProduct("p1", "Cola", "cola")
        assertTrue(InventoryTastePickerSupport.isCurrentProduct("p1", "p1"))
        assertFalse(InventoryTastePickerSupport.isCurrentProduct("p1", "p2"))
        assertTrue(InventoryTastePickerSupport.optionLabel(product, isCurrent = true).contains("текущий"))
    }
}
