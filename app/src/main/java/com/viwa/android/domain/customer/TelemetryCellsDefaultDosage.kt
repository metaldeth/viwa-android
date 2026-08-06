package com.viwa.android.domain.customer

import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.customer.DrinkDosage

/**
 * Единая локальная база дозировки для customer UI и service recipe preview.
 * Не server-managed recipe; совпадает с шаблоном в [TelemetryCellsSnapshotAdapter].
 */
object TelemetryCellsDefaultDosage {
    const val RECIPE_DRINK_VOLUME_ML = 300
    const val RECIPE_PRODUCT_ML = 30.0
    const val RECIPE_WATER_ML = 270.0

    const val LOCAL_TEMPLATE_NOTE =
        "Локальный Android-шаблон (300 мл), не рецепт с сервера и не telemetry recipe snapshot."

    val template: DrinkDosage =
        DrinkDosage(
            conversionFactor = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
            drinkVolume = RECIPE_DRINK_VOLUME_ML,
            product = RECIPE_PRODUCT_ML,
            water = RECIPE_WATER_ML,
        )

    fun templateWithConversionFactor(conversionFactor: Double): DrinkDosage =
        template.copy(conversionFactor = conversionFactor)
}
