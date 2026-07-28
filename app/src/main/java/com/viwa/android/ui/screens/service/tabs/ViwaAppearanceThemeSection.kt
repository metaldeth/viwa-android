package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.viwa.android.ui.components.HsvColorPicker
import com.viwa.android.ui.components.argbToComposeColor
import com.viwa.android.ui.components.colorToRgbHex
import com.viwa.android.ui.components.composeColorToArgb
import com.viwa.android.ui.screens.service.ServiceUiState
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.theme.LocalCustomerPrimaryButtonColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider

private const val SAVE_DEBOUNCE_MS = 300L

@Composable
fun ViwaAppearanceThemeSection(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var primarySaveJob by remember { mutableStateOf<Job?>(null) }
    var flowSaveJob by remember { mutableStateOf<Job?>(null) }

    val primaryColor = Color(state.customerPrimaryButtonArgb)
    val flowStripColor = argbToComposeColor(state.flowStripRgbArgb)

    fun previewPrimary(color: Color) {
        val argb = composeColorToArgb(color)
        viewModel.previewCustomerPrimaryRgb(
            (argb shr 16) and 0xFF,
            (argb shr 8) and 0xFF,
            argb and 0xFF,
        )
    }

    fun schedulePrimaryPersist() {
        primarySaveJob?.cancel()
        primarySaveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                viewModel.persistCustomerPrimaryButtonColor()
            }
    }

    fun onPrimaryColorChange(color: Color) {
        previewPrimary(color)
        schedulePrimaryPersist()
    }

    fun previewFlowStrip(color: Color) {
        val argb = composeColorToArgb(color)
        viewModel.previewFlowStripRgb(
            (argb shr 16) and 0xFF,
            (argb shr 8) and 0xFF,
            argb and 0xFF,
        )
    }

    fun scheduleFlowPersist() {
        flowSaveJob?.cancel()
        flowSaveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                viewModel.persistFlowStripRgb()
            }
    }

    fun onFlowStripColorChange(color: Color) {
        previewFlowStrip(color)
        scheduleFlowPersist()
    }

    val previewScheme =
        (if (state.isDarkTheme) darkColorScheme() else lightColorScheme())
            .copy(primary = primaryColor)

    CompositionLocalProvider(LocalCustomerPrimaryButtonColor provides primaryColor) {
        MaterialTheme(colorScheme = previewScheme) {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppearanceHeader()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AppearanceColorPanel(
                        title = "Интерфейс",
                        subtitle = "Брендовый цвет customer UI и режим темы.",
                        color = primaryColor,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.isDarkTheme,
                                onClick = { viewModel.setDarkTheme(true) },
                                label = { Text("Тёмная") },
                            )
                            FilterChip(
                                selected = !state.isDarkTheme,
                                onClick = { viewModel.setDarkTheme(false) },
                                label = { Text("Светлая") },
                            )
                        }
                        HsvColorPicker(
                            color = primaryColor,
                            onColorChange = ::onPrimaryColorChange,
                            label = "Цвет интерфейса",
                            wheelSize = 220.dp,
                        )
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Пример кнопки")
                        }
                    }
                    AppearanceColorPanel(
                        title = "RGB-лента",
                        subtitle = "Цвет Flow-подсветки. Сохранение отправляет SetFlowRgb (0xD2).",
                        color = flowStripColor,
                        modifier = Modifier.weight(1f),
                    ) {
                        HsvColorPicker(
                            color = flowStripColor,
                            onColorChange = ::onFlowStripColorChange,
                            label = "Цвет RGB",
                            wheelSize = 220.dp,
                        )
                        FlowStripPreview(color = flowStripColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Внешний вид",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Слева брендовый цвет интерфейса, справа RGB-подсветка ленты.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearanceColorPanel(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(color, CircleShape),
                )
            }
            Text(
                colorToRgbHex(color),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun FlowStripPreview(color: Color) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .background(color, RoundedCornerShape(999.dp)),
    )
}
