package com.viwa.android.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryPriceFormatTest {

    @Test
    fun `formats whole rubles without kopecks suffix noise`() {
        assertEquals("99 ₽", InventoryPriceFormat.formatKopecks(9900))
    }

    @Test
    fun `retains kopecks in display`() {
        assertEquals("99,50 ₽", InventoryPriceFormat.formatKopecks(9950))
    }

    @Test
    fun `returns dash for null`() {
        assertEquals("—", InventoryPriceFormat.formatKopecks(null))
    }
}
