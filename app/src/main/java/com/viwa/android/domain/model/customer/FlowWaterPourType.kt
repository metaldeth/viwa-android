package com.viwa.android.domain.model.customer

/**
 * Переключатель воды для команды WaterPourByTouch (0xD0), байт SelW.
 * 0 — фильтрованная, 1 — холодная, 2 — газированная.
 */
enum class FlowWaterPourType(val selByte: Int) {
    Filtered(0),
    Cold(1),
    Sparkling(2),
    ;

    val shortLabel: String
        get() =
            when (this) {
                Filtered -> "Фильтр"
                Cold -> "Холодная"
                Sparkling -> "Газированная"
            }
}

/** Синхронизация верхней панели (DrinkWaterOption) ↔ тип налива 0xD0. */
fun DrinkWaterOption.toFlowWaterPourType(): FlowWaterPourType =
    when (this) {
        DrinkWaterOption.STANDARD -> FlowWaterPourType.Filtered
        DrinkWaterOption.COLD -> FlowWaterPourType.Cold
        DrinkWaterOption.SPARK -> FlowWaterPourType.Sparkling
    }

fun FlowWaterPourType.toDrinkWaterOption(): DrinkWaterOption =
    when (this) {
        FlowWaterPourType.Filtered -> DrinkWaterOption.STANDARD
        FlowWaterPourType.Cold -> DrinkWaterOption.COLD
        FlowWaterPourType.Sparkling -> DrinkWaterOption.SPARK
    }
