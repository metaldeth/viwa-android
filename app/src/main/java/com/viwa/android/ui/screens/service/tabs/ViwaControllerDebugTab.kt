package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.hardware.controller.ControllerTrafficDirection
import com.viwa.android.hardware.controller.ControllerTrafficEntry
import com.viwa.android.ui.screens.service.ServiceUiState
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.screens.service.serviceMenuSp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
@Composable
fun ViwaControllerDebugTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    val entries by viewModel.controllerTrafficFlow.collectAsStateWithLifecycle()
    val reversed = remember(entries) { entries.asReversed() }
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
        contentColor = scheme.onBackground,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Column(
            modifier =
                Modifier
                    .weight(0.52f)
                    .fillMaxHeight(),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Лог команд (TX/RX)", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { viewModel.clearControllerTrafficLog() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Очистить") }
            }
            Spacer(Modifier.height(8.dp))
            val expanded = remember { mutableStateMapOf<Int, Boolean>() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (reversed.isEmpty()) {
                    item {
                        Text(
                            "Нет записей. Выполните действия с контроллером.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    itemsIndexed(reversed, key = { _, e -> e.id }) { _, e ->
                        val ex = expanded[e.id] == true
                        ControllerTrafficRow(
                            entry = e,
                            expanded = ex,
                            onToggle = { expanded[e.id] = !ex },
                        )
                    }
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val mockLabel =
                if (state.useMockController) {
                    "Мок контроллера: Вкл"
                } else {
                    "Мок контроллера: Выкл"
                }
            Text(mockLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Переключить мок", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.useMockController,
                    onCheckedChange = viewModel::setUseMockController,
                )
            }
            Text(
                "Основной переключатель — «Настройки → Режим разработки».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Статус", style = MaterialTheme.typography.titleSmall)
            Text(
                "Режим: ${state.controllerDebugMode ?: "—"} · Ошибка: ${state.controllerDebugError ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Последнее учтённое значение: ${state.controllerDebugWaterMl?.let { "+$it" } ?: "—"} мл",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.controllerDebugWaterLastDeltaMl?.let { delta ->
                Text(
                    "Последнее приращение lifetime: +$delta мл",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.controllerDebugLifetimeWaterMl?.let { total ->
                Text(
                    "Lifetime (WATER_USAGE_ML): ${"%.0f".format(total)} мл",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.controllerDebugBanner?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            HorizontalDivider()
            OutlinedButton(onClick = viewModel::controllerDebugReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Переподключить")
            }
            OutlinedButton(onClick = viewModel::controllerDebugCheck, modifier = Modifier.fillMaxWidth()) {
                Text("Чек контроллера")
            }
            OutlinedButton(onClick = viewModel::controllerDebugReadErrors, modifier = Modifier.fillMaxWidth()) {
                Text("Читать ошибки")
            }
            OutlinedButton(onClick = viewModel::controllerDebugReadMode, modifier = Modifier.fillMaxWidth()) {
                Text("Читать режим")
            }
            OutlinedButton(
                onClick = viewModel::controllerDebugSendRecipe,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отправить рецепт выбранного напитка")
            }
            Text(
                "ChooseDrink из вкладки «Время готовки» (300 мл, tof 0). Без выбранного напитка команда не отправляется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = viewModel::controllerDebugStartPreparing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Начать готовить")
            }
            Text("StartDrinkPreparing, мок: 10 с", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = viewModel::controllerDebugServiceMode, modifier = Modifier.fillMaxWidth()) {
                Text("Режим сервис")
            }
            OutlinedButton(onClick = viewModel::controllerDebugAutoMode, modifier = Modifier.fillMaxWidth()) {
                Text("Авто режим")
            }
            WaterCounterAccountSection(
                busy = state.controllerDebugBusy,
                onConfirm = viewModel::controllerDebugAccountWaterCounter,
            )
            HorizontalDivider()
            Text("Flow станция", style = MaterialTheme.typography.titleSmall)
            val tempLabel = if (state.flowTemperatureSensor0C != null && state.flowTemperatureSensor1C != null) {
                "T0=${state.flowTemperatureSensor0C}°C · T1=${state.flowTemperatureSensor1C}°C"
            } else {
                "—"
            }
            Text(
                "Температура: $tempLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = viewModel::controllerDebugReadFlowTemperature,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Читать температуру (0xD1)")
            }
            OutlinedButton(
                onClick = viewModel::controllerDebugSetFlowRgb,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Установить RGB 128,0,255 (0xD2)")
            }
            Text(
                "Постоянный цвет ленты и сохранение — вкладка «Настройки» → «Тема», блок под цветом кнопки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val bucketLabel = when (state.flowBucketIsFull) {
                true -> "заполнено"
                false -> "не заполнено"
                null -> "—"
            }
            Text(
                "Ведро: $bucketLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = viewModel::controllerDebugReadFlowBucket,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Статус ведра (0xD3)")
            }
        }
        }
    }
}

@Composable
private fun ControllerTrafficRow(
    entry: ControllerTrafficEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val isTx = entry.direction == ControllerTrafficDirection.TX
    val scheme = MaterialTheme.colorScheme
    val dirColor = if (isTx) scheme.primary else scheme.tertiary
    val baseBg = if (isTx) scheme.primaryContainer else scheme.tertiaryContainer
    val bg = baseBg.copy(alpha = if (expanded) 0.95f else 0.72f)
    val cmdByte = runCatching { entry.commandHex.removePrefix("0x").toInt(16) }.getOrDefault(0)
    val frameStartByte = if (isTx) 0xfe else 0xd5
    val fullFrame = buildList {
        add(frameStartByte)
        add(entry.payloadBytes.size + 1)
        add(cmdByte)
        addAll(entry.payloadBytes)
    }
    val summary =
        "${entry.commandName} ${entry.commandHex} · ${fullFrame.size} B"
    val hexFull = fullFrame.joinToString(" ") { b -> "%02x".format(b and 0xff) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isTx) "TX" else "RX",
                    color = dirColor,
                    fontSize = serviceMenuSp(10),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier
                            .background(dirColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    TIME_FORMAT.format(Date(entry.timestampMs)),
                    fontSize = serviceMenuSp(11),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    fontSize = serviceMenuSp(12),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = dirColor,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = dirColor.copy(alpha = 0.25f))
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(if (isTx) "TX frame:  " else "RX frame:  ")
                        append(hexFull)
                    },
                    fontSize = serviceMenuSp(11),
                    fontFamily = FontFamily.Monospace,
                    lineHeight = serviceMenuSp(16),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("           ")
                        fullFrame.forEachIndexed { i, _ ->
                            val label = when (i) {
                                0 -> "STX"
                                1 -> "LEN"
                                2 -> "CMD"
                                else -> "d${i - 2}  ".take(4)
                            }
                            append(label.padEnd(3))
                        }
                    },
                    fontSize = serviceMenuSp(9),
                    fontFamily = FontFamily.Monospace,
                    color = dirColor.copy(alpha = 0.6f),
                    lineHeight = serviceMenuSp(12),
                )
            }
        }
    }
}

@Composable
private fun WaterCounterAccountSection(
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    var confirmVisible by remember { mutableStateOf(false) }
    Text(
        "Считать показание контроллера, добавить в lifetime и сбросить счётчик на плате.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (busy) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            Text("Считывание…", style = MaterialTheme.typography.bodySmall)
        }
    } else if (!confirmVisible) {
        OutlinedButton(
            onClick = { confirmVisible = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text("Считать, учесть и сбросить")
        }
    } else {
        Text(
            "Добавить текущее показание в lifetime и сбросить контроллер?",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmVisible = false },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                Text("Отмена")
            }
            Button(
                onClick = {
                    confirmVisible = false
                    onConfirm()
                },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) {
                Text("Подтвердить")
            }
        }
    }
}
