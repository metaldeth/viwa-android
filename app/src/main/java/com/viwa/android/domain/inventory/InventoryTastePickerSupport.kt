package com.viwa.android.domain.inventory

import com.viwa.android.domain.model.TelemetryProduct

object InventoryTastePickerSupport {
    fun isCurrentProduct(
        rowProductUuid: String?,
        productUuid: String,
    ): Boolean = rowProductUuid == productUuid

    fun optionLabel(
        product: TelemetryProduct,
        isCurrent: Boolean,
    ): String =
        if (isCurrent) {
            "${product.name} (${product.tasteMediaKey}) · текущий"
        } else {
            "${product.name} (${product.tasteMediaKey})"
        }
}
