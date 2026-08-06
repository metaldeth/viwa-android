package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.services.drink.DrinkPreparationCalculations
import com.viwa.android.ui.screens.service.PreparingStatsDrinkOption
import com.viwa.android.ui.screens.service.PreparingStatsHistoryRow
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.screens.service.SettingsColumn
import com.viwa.android.ui.screens.service.SettingsTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun ViwaPreparingTimeTab(viewModel: ServiceViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshPreparingStatsData()
    }

    val selected = s.preparingStatsDrinks.firstOrNull { it.tasteId == s.preparingStatsSelectedTasteId }

    SettingsColumn {
        Text("Время готовки", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Факт измеряется по ответу контроллера: от состояния BEGIN до DRINK_PREPARING_SUCCESS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Автовыход с экрана готовки", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Если SUCCESS с контроллера не пришёл, через указанное число минут экран готовки закроется " +
                "(как при аварийном выходе). 0 — только ручной выход и секретная зона в углу.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            label = "Минут до автовыхода (по умолчанию 5)",
            value = s.preparingAutoExitMinutesInput,
            onValueChange = { viewModel.setPreparingAutoExitMinutesInput(it) },
            keyboardType = KeyboardType.Number,
            fieldKey = "preparing_auto_exit_minutes",
            maxLength = 3,
        )
        Button(onClick = { viewModel.savePreparingAutoExitMinutes() }) {
            Text("Сохранить")
        }
        s.preparingAutoExitSaveBanner?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (s.preparingAutoExitSaveBannerIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.preparingStatsDrinks.forEach { option ->
                val isSelected = option.tasteId == s.preparingStatsSelectedTasteId
                if (isSelected) {
                    Button(onClick = { }, enabled = false) { Text(option.title) }
                } else {
                    OutlinedButton(onClick = { viewModel.setPreparingStatsSelectedTasteId(option.tasteId) }) {
                        Text(option.title)
                    }
                }
            }
        }

        if (s.preparingStatsBusy) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(strokeWidth = 2.dp)
        }

        s.preparingStatsBanner?.let { banner ->
            Spacer(Modifier.height(12.dp))
            Text(
                banner,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (s.preparingStatsBannerIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Рецепт и расчёт", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (selected == null) {
            Text(
                "Выберите напиток для расчёта.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            RecipeDiagnosticsBlock(selected = selected, flowRateMlPerSec = s.preparingStatsFlowRateMlPerSec)
        }

        Spacer(Modifier.height(16.dp))
        Text("Расчёт времени", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (selected == null) {
            Text(
                "Выберите напиток для расчёта времени.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FormulaBlock(selected = selected, flowRateMlPerSec = s.preparingStatsFlowRateMlPerSec)
        }

        Spacer(Modifier.height(20.dp))
        Text("Последние 10 готовок выбранного напитка", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (s.preparingStatsHistory.isEmpty()) {
            Text(
                "Нет измеренных готовок.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            s.preparingStatsHistory.forEach { row ->
                HistoryRow(row = row)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RecipeDiagnosticsBlock(
    selected: PreparingStatsDrinkOption,
    flowRateMlPerSec: Double?,
) {
    Text(
        "База рецепта: drinkVolume=${selected.recipeDrinkVolumeMl} мл, " +
            "water=${"%.1f".format(selected.recipeWaterMl)} мл, " +
            "product=${"%.1f".format(selected.recipeProductMl)} мл, " +
            "conversionFactor=${"%.4f".format(selected.conversionFactor)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(6.dp))
    SelectedVolumeRecipeLine(selected = selected, drinkVolumeMl = 300, flowRateMlPerSec = flowRateMlPerSec)
    SelectedVolumeRecipeLine(selected = selected, drinkVolumeMl = 700, flowRateMlPerSec = flowRateMlPerSec)
    Spacer(Modifier.height(6.dp))
    val flowRateText = flowRateMlPerSec?.let { "%.2f".format(it) } ?: "—"
    Text(
        "flowRate=$flowRateText мл/с · контейнер #${selected.containerNumber}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SelectedVolumeRecipeLine(
    selected: PreparingStatsDrinkOption,
    drinkVolumeMl: Int,
    flowRateMlPerSec: Double?,
) {
    val ratio = drinkVolumeMl.toDouble() / selected.recipeDrinkVolumeMl.toDouble()
    val waterMl =
        DrinkPreparationCalculations.waterMlForDrink(
            dosageWaterMl = selected.recipeWaterMl,
            drinkVolumeMl = drinkVolumeMl,
            recipeDrinkVolumeMl = selected.recipeDrinkVolumeMl,
        )
    val productMl = selected.recipeProductMl * ratio
    val dispenserSec =
        if (selected.conversionFactor > 0.0) {
            productMl / selected.conversionFactor
        } else {
            Double.NaN
        }
    val timeSec =
        if (flowRateMlPerSec != null && flowRateMlPerSec > 0.0) {
            DrinkPreparationCalculations.preparingTimeSec(waterMl, flowRateMlPerSec)
        } else {
            null
        }
    val choosePreview =
        buildString {
            append("ChooseDrink preview $drinkVolumeMl мл: ")
            append("water=${"%.1f".format(waterMl)} мл, product=${"%.1f".format(productMl)} мл")
            if (dispenserSec.isFinite()) {
                append(", dispenser=${"%.2f".format(dispenserSec)} с")
            }
            if (timeSec != null) {
                append(", prepTime=$timeSec с")
            }
        }
    Text(choosePreview, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FormulaBlock(
    selected: PreparingStatsDrinkOption,
    flowRateMlPerSec: Double?,
) {
    val flowRateText = flowRateMlPerSec?.let { "%.2f".format(it) } ?: "—"
    Text(
        "Формула: timeSec = round(waterMl / flowRate), где waterMl = recipeWater × (drinkVolume / recipeDrinkVolume).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Текущие значения: recipeWater=${"%.1f".format(selected.recipeWaterMl)} мл, recipeDrinkVolume=${selected.recipeDrinkVolumeMl} мл, flowRate=$flowRateText мл/с.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(6.dp))
    FormulaLine(
        drinkVolumeMl = 300,
        selected = selected,
        flowRateMlPerSec = flowRateMlPerSec,
    )
    FormulaLine(
        drinkVolumeMl = 700,
        selected = selected,
        flowRateMlPerSec = flowRateMlPerSec,
    )
}

@Composable
private fun FormulaLine(
    drinkVolumeMl: Int,
    selected: PreparingStatsDrinkOption,
    flowRateMlPerSec: Double?,
) {
    if (flowRateMlPerSec == null || flowRateMlPerSec <= 0.0) {
        Text(
            "$drinkVolumeMl мл: нет калибровки воды (flowRate ≤ 0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val waterMl =
        DrinkPreparationCalculations.waterMlForDrink(
            dosageWaterMl = selected.recipeWaterMl,
            drinkVolumeMl = drinkVolumeMl,
            recipeDrinkVolumeMl = selected.recipeDrinkVolumeMl,
        )
    val timeSec = DrinkPreparationCalculations.preparingTimeSec(waterMl, flowRateMlPerSec)
    Text(
        "$drinkVolumeMl мл: waterMl=${"%.1f".format(selected.recipeWaterMl)}×($drinkVolumeMl/${selected.recipeDrinkVolumeMl})=${"%.1f".format(waterMl)}; time=round(${"%.1f".format(waterMl)}/${"%.2f".format(flowRateMlPerSec)})=$timeSec c.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun HistoryRow(row: PreparingStatsHistoryRow) {
    val ts = rememberDate(row.timestampEpochMs)
    val deviationSign = if (row.deltaSec > 0) "+" else ""
    val deltaText =
        "$deviationSign${"%.2f".format(row.deltaSec)} c (${deviationSign}${"%.1f".format(row.deltaPercent)}%)"
    val deviationColor =
        if (abs(row.deltaSec) <= 1.0) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        }
    Text(
        "$ts · ${row.volumeMl} мл · факт ${"%.2f".format(row.actualTimeSec)} c · расчёт ${row.expectedTimeSec} c",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Отклонение: $deltaText",
        style = MaterialTheme.typography.bodySmall,
        color = deviationColor,
    )
}

private fun rememberDate(epochMs: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(epochMs))
}
