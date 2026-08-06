package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryProduct

object InventoryCellTasteChange {
    fun isTasteChangeApplicable(row: MvpInventoryTableRow): Boolean = true

    /**
     * Меняет продукт/вкус ячейки, сохраняя цены 300/700 мл и остальные поля ячейки.
     */
    fun applyProductAssignment(
        cell: TelemetryCell,
        product: TelemetryProduct,
    ): TelemetryCell =
        cell.copy(
            productUuid = product.uuid,
            productName = product.name,
            tasteMediaKey = product.tasteMediaKey,
        )

    fun preservesPrices(
        before: TelemetryCell,
        after: TelemetryCell,
    ): Boolean =
        before.dosage1Price == after.dosage1Price &&
            before.dosage2Price == after.dosage2Price &&
            before.volume == after.volume &&
            before.blockVolume == after.blockVolume &&
            before.sosVolume == after.sosVolume &&
            before.maxVolume == after.maxVolume &&
            before.conversionFactor == after.conversionFactor
}
