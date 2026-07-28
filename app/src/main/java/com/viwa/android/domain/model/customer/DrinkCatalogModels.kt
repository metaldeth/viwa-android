package com.viwa.android.domain.model.customer

import androidx.compose.runtime.Immutable

/** Дозировка → `Dosage`. */
@Immutable
data class DrinkDosage(
    val conversionFactor: Double,
    val drinkVolume: Int,
    val product: Double,
    val water: Double,
)

@Immutable
data class DrinkTaste(
    val id: Int,
    val name: String,
    val mediaKey: String?,
    val hexColor: String?,
)

@Immutable
data class DrinkPrice(
    val volume: Int,
 /** Цена в рублях (. */
    val priceRub: Int,
)

@Immutable
data class DrinkProduct(
    val id: Int,
    val name: String,
    val taste: DrinkTaste,
    val dosage: DrinkDosage,
    val dPrices: List<DrinkPrice>,
)

/**
 * Контейнер для UI и рецепта ChooseDrink (номер ячейки, остаток — как `ContainerDTO` в wiva).
 */
@Immutable
data class DrinkContainer(
    val containerNumber: Int,
    val sodaStatus: Boolean?,
    val product: DrinkProduct,
    /** Stable catalog UUID from telemetry cells snapshot. */
    val productUuid: String,
    val volumeMl: Int?,
    val minVolumeMl: Int?,
    val isActive: Boolean = true,
)

enum class DrinkWaterOption {
    STANDARD,
    COLD,
    SPARK,
}

enum class DrinkConcentration {
    Weak,
    Standard,
    Strong,
}

fun DrinkConcentration.toRatio(): Double =
    when (this) {
        DrinkConcentration.Weak -> 0.9
        DrinkConcentration.Standard -> 1.0
        DrinkConcentration.Strong -> 1.1
    }

fun DrinkWaterOption.toTofByte(): Int =
    when (this) {
        DrinkWaterOption.STANDARD -> 0
        DrinkWaterOption.COLD -> 1
        DrinkWaterOption.SPARK -> 2
    }

fun DrinkContainer.isUnavailable(): Boolean {
    val v = volumeMl ?: 0
    val min = minVolumeMl ?: 0
    return v < min
}
