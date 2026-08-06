package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.MvpInventoryTableRow

object InventoryVolumeDraftMerge {
    fun mergeRowsIntoDraft(
        rows: List<MvpInventoryTableRow>,
        currentDraft: Map<Int, String>,
        dirtyCellNumbers: Set<Int>,
    ): Map<Int, String> {
        if (rows.isEmpty()) return currentDraft
        val next = currentDraft.toMutableMap()
        for (row in rows) {
            if (row.cellNumber in dirtyCellNumbers) {
                if (!next.containsKey(row.cellNumber)) {
                    next[row.cellNumber] = row.volumeMl.toString()
                }
            } else {
                next[row.cellNumber] = row.volumeMl.toString()
            }
        }
        return next
    }
}
