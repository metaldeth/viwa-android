package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.CellVolumeStatus
import com.viwa.android.domain.model.MvpInventoryTableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryVolumeDraftMergeTest {

    @Test
    fun `preserves dirty draft when rows refresh`() {
        // given
        val rows =
            listOf(
                row(cellNumber = 1, volumeMl = 100),
                row(cellNumber = 2, volumeMl = 200),
            )
        val currentDraft = mapOf(1 to "150", 2 to "250")
        val dirty = setOf(1)

        // when
        val merged =
            InventoryVolumeDraftMerge.mergeRowsIntoDraft(
                rows = rows,
                currentDraft = currentDraft,
                dirtyCellNumbers = dirty,
            )

        // then
        assertEquals("150", merged[1])
        assertEquals("200", merged[2])
    }

    @Test
    fun `fills missing non-dirty cells from snapshot`() {
        // given
        val rows = listOf(row(cellNumber = 3, volumeMl = 777))

        // when
        val merged =
            InventoryVolumeDraftMerge.mergeRowsIntoDraft(
                rows = rows,
                currentDraft = emptyMap(),
                dirtyCellNumbers = emptySet(),
            )

        // then
        assertEquals("777", merged[3])
    }

    private fun row(cellNumber: Int, volumeMl: Int): MvpInventoryTableRow =
        MvpInventoryTableRow(
            uuid = "u$cellNumber",
            cellNumber = cellNumber,
            productUuid = "p",
            productName = "P",
            tasteMediaKey = "t",
            price300Kopecks = null,
            price700Kopecks = null,
            volumeMl = volumeMl,
            blockVolume = 0,
            sosVolume = 0,
            maxVolume = 5000,
            volumeStatus = CellVolumeStatus.NORMAL,
        )
}
