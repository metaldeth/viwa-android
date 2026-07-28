package com.viwa.android.domain.customer

import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste

/**
 * Snapshot MVP cells → [DrinkContainer] для customer UI (без legacy merge-inventory).
 * Цены: копейки → рубли; blockVolume → minVolumeMl для [DrinkContainer.isUnavailable].
 */
object TelemetryCellsSnapshotAdapter {
    private val defaultDosageTemplate =
        DrinkDosage(
            conversionFactor = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
            drinkVolume = 300,
            product = 30.0,
            water = 270.0,
        )

    fun toDrinkContainers(snapshot: TelemetryCellsSnapshot): List<DrinkContainer> =
        snapshot.cells
            .sortedBy { it.cellNumber }
            .mapNotNull { cell -> toDrinkContainer(cell, snapshot.products) }

    fun toDrinkContainer(
        cell: TelemetryCell,
        products: List<TelemetryProduct> = emptyList(),
    ): DrinkContainer? {
        val productUuid = cell.productUuid ?: return null
        val catalogProduct = products.find { it.uuid == productUuid }
        val tasteMediaKey = cell.tasteMediaKey ?: catalogProduct?.tasteMediaKey
        val productName = cell.productName ?: catalogProduct?.name ?: return null
        val prices = buildPrices(cell)
        if (prices.isEmpty()) return null

        return DrinkContainer(
            containerNumber = cell.cellNumber,
            sodaStatus = null,
            productUuid = productUuid,
            product =
                DrinkProduct(
                    id = cell.cellNumber,
                    name = productName,
                    taste =
                        DrinkTaste(
                            id = cell.cellNumber,
                            name = productName,
                            mediaKey = tasteMediaKey,
                            hexColor = null,
                        ),
                    dosage = defaultDosageTemplate.copy(conversionFactor = cell.conversionFactor),
                    dPrices = prices,
                ),
            volumeMl = cell.volume,
            minVolumeMl = cell.blockVolume,
            isActive = true,
        )
    }

    private fun buildPrices(cell: TelemetryCell): List<DrinkPrice> {
        val out = ArrayList<DrinkPrice>(2)
        cell.dosage1Price?.let { out.add(DrinkPrice(volume = 300, priceRub = kopecksToRubles(it))) }
        cell.dosage2Price?.let { out.add(DrinkPrice(volume = 700, priceRub = kopecksToRubles(it))) }
        return out
    }

    private fun kopecksToRubles(kopecks: Int): Int = kopecks / 100
}
