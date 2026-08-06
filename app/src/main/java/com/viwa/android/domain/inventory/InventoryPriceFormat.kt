package com.viwa.android.domain.inventory

object InventoryPriceFormat {
    fun formatKopecks(kopecks: Int?): String {
        if (kopecks == null) return "—"
        val rub = kopecks / 100
        val kop = kopecks % 100
        return if (kop == 0) {
            "$rub ₽"
        } else {
            "$rub,${kop.toString().padStart(2, '0')} ₽"
        }
    }
}
