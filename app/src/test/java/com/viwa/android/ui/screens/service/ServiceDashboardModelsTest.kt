package com.viwa.android.ui.screens.service

import com.viwa.android.domain.model.CellVolumeStatus
import com.viwa.android.domain.model.MvpInventoryTableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDashboardModelsTest {
    @Test
    fun `buildServiceDashboardCells maps six cells from inventory rows`() {
        val rows =
            listOf(
                MvpInventoryTableRow(
                    uuid = "cell-1",
                    cellNumber = 1,
                    productUuid = "p1",
                    productName = "Cola",
                    tasteMediaKey = "cola",
                    price300Kopecks = 15000,
                    price700Kopecks = 25000,
                    volumeMl = 2500,
                    blockVolume = 500,
                    sosVolume = 1000,
                    maxVolume = 5000,
                    volumeStatus = CellVolumeStatus.NORMAL,
                ),
            )

        val cells = buildServiceDashboardCells(rows)

        assertEquals(6, cells.size)
        assertTrue(cells.first { it.cellNumber == 1 }.hasData)
        assertEquals("Cola", cells.first { it.cellNumber == 1 }.catalogTitle)
        assertFalse(cells.first { it.cellNumber == 2 }.hasData)
    }
}
