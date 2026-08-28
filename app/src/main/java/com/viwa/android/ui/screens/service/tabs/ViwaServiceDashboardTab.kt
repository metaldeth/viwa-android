package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.viwa.android.ui.screens.customer.ViwaElectronAssets
import java.util.Locale
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.ui.screens.service.ServiceDashboardCellUi
import com.viwa.android.ui.screens.service.serviceMenuSp
import com.viwa.android.ui.screens.service.ServiceUiState
import com.viwa.android.ui.screens.service.TelemetryConnectionUiState
import com.viwa.android.ui.screens.service.ServiceViewModel

/**. */
private val DashboardMediaKeyHexOverride: Map<String, String> =
    mapOf(
        "strawberry-lemongrass" to "#F44336",
        "lime" to "#63E515",
        "lemon" to "#D7D717",
        "peach-mango" to "#E5AE8A",
        "peach" to "#FFB74D",
        "mint" to "#66BB6A",
        "pineapple" to "#FFC107",
        "coconut" to "#74543D",
    )

/**
 * Всё на одном экране без вертикальной прокрутки: слева связь/интеграции/вода, справа сетка 3×2 ячеек.
 */
@Composable
fun ViwaServiceDashboardTab(
    viewModel: ServiceViewModel,
) {
    val cells by viewModel.dashboardCells.collectAsStateWithLifecycle()
    val totalWaterMl by viewModel.totalWaterUsageMl.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryConnectionUi.collectAsStateWithLifecycle()
    val controllerUsb by viewModel.controllerPhysicalConnected.collectAsStateWithLifecycle()
    val scannerSerialActive by viewModel.scannerSerialActive.collectAsStateWithLifecycle()
    val equipmentUsbPath by viewModel.equipmentSelectedUsbPath.collectAsStateWithLifecycle()
    val terminalLine by viewModel.terminalVendStatusLine.collectAsStateWithLifecycle()
    val rfidUsbPath by viewModel.rfidSelectedUsbPath.collectAsStateWithLifecycle()
    val serviceState by viewModel.state.collectAsStateWithLifecycle()

    var actionBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshServiceDashboard()
    }

    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Обзор автомата",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onBackground,
                )
                Text(
                    "${"%.0f".format(totalWaterMl)} мл · ${"%.1f".format(totalWaterMl / 1000.0)} л",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
            }

            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardSidePanel(
                    modifier = Modifier.weight(0.40f),
                    telemetry = telemetry,
                    controllerUsb = controllerUsb,
                    useMockController = serviceState.useMockController,
                    equipmentUsbPath = equipmentUsbPath,
                    scannerSerialActive = scannerSerialActive,
                    terminalLine = terminalLine,
                    rfidUsbPath = rfidUsbPath,
                    rfidConnected = serviceState.rfidConnected,
                    serviceState = serviceState,
                    totalWaterMl = totalWaterMl,
                )
                Column(
                    modifier = Modifier.weight(0.60f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cells.take(3).forEach { cell ->
                            CompactDashboardCell(
                                cell = cell,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onFillToFull = { maxMl ->
                                    actionBanner = null
                                    viewModel.fillInventoryCellToMax(cell.cellNumber, maxMl) { ok, msg ->
                                        actionBanner = msg
                                        if (ok) viewModel.refreshServiceDashboard()
                                    }
                                },
                            )
                        }
                    }
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cells.drop(3).take(3).forEach { cell ->
                            CompactDashboardCell(
                                cell = cell,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onFillToFull = { maxMl ->
                                    actionBanner = null
                                    viewModel.fillInventoryCellToMax(cell.cellNumber, maxMl) { ok, msg ->
                                        actionBanner = msg
                                        if (ok) viewModel.refreshServiceDashboard()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            actionBanner?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DashboardSidePanel(
    modifier: Modifier = Modifier,
    telemetry: TelemetryConnectionUiState,
    controllerUsb: Boolean,
    useMockController: Boolean,
    equipmentUsbPath: String,
    scannerSerialActive: Boolean,
    terminalLine: String,
    rfidUsbPath: String,
    rfidConnected: Boolean,
    serviceState: ServiceUiState,
    totalWaterMl: Double,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = scheme.surfaceVariant.copy(alpha = 0.45f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Связь", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill("WS", telemetry.connected)
            }
            Text(
                if (telemetry.connected) telemetry.label else (telemetry.error ?: telemetry.label),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider(color = scheme.outline.copy(alpha = 0.25f))

            Text("Оборудование", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            DashboardEquipmentLine(
                title = "Контроллер",
                ok = !useMockController && controllerUsb,
                line1 = dashboardControllerLine1(useMockController, controllerUsb),
                line2 = dashboardControllerLine2(useMockController, equipmentUsbPath, controllerUsb),
            )
            DashboardEquipmentLine(
                title = "Сканер QR",
                ok = scannerSerialActive,
                line1 =
                    if (scannerSerialActive) {
                        "USB-serial, чтение штрихкодов"
                    } else {
                        "Нет активного чтения с порта"
                    },
                line2 = dashboardScannerPortHint(serviceState.scannerPortAssignments),
            )
            DashboardEquipmentLine(
                title = "Платёжник",
                ok = dashboardPaymentOk(terminalLine),
                line1 = "PAX (статус по шине контроллера 0x56)",
                line2 = terminalLine,
            )
            DashboardEquipmentLine(
                title = "Сканер RFID",
                ok = rfidConnected,
                line1 =
                    if (rfidConnected) {
                        "UART/USB-serial открыт"
                    } else {
                        "Нет соединения"
                    },
                line2 = dashboardRfidHint(rfidUsbPath, rfidConnected),
            )

            HorizontalDivider(color = scheme.outline.copy(alpha = 0.25f))

            Text("Интеграции", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusPill(
                    "СБП",
                    serviceState.sbpSpotId.isNotBlank() && serviceState.sbpKey.isNotBlank(),
                )
                StatusPill(
                    "Нано",
                    serviceState.nanoKassaId.isNotBlank() &&
                        serviceState.nanoKassaToken.isNotBlank() &&
                        serviceState.nanoLastIntegrationVerifyOk,
                )
                StatusPill("MAX", serviceState.maxExtApiToken.isNotBlank())
            }

            HorizontalDivider(color = scheme.outline.copy(alpha = 0.25f))

            Text("Вода (накоп.)", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            Text(
                "${"%.0f".format(totalWaterMl)} мл",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary,
            )
            Text(
                "После готовки: чтение счётчика → +к сумме → сброс на контроллере.",
                style = MaterialTheme.typography.bodySmall,
                lineHeight = serviceMenuSp(16),
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashboardEquipmentLine(
    title: String,
    ok: Boolean,
    line1: String,
    line2: String,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
            Text(
                line1,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                line2,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (ok) scheme.primaryContainer else scheme.errorContainer.copy(alpha = 0.65f),
        ) {
            Text(
                if (ok) "OK" else "!",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) scheme.onPrimaryContainer else scheme.onErrorContainer,
            )
        }
    }
}

private fun dashboardControllerLine1(
    useMock: Boolean,
    physical: Boolean,
): String =
    when {
        useMock -> "Режим мока (без UART)"
        physical -> "USB UART, сессия с платой управления"
        else -> "Физическое подключение не активно"
    }

private fun dashboardControllerLine2(
    useMock: Boolean,
    path: String,
    physical: Boolean,
): String =
    when {
        useMock -> "Тумблер «Мок контроллера» в настройках"
        physical && path.isNotBlank() -> path.takeLast(44)
        path.isNotBlank() -> "Порт задан — сессия UART не открыта"
        else -> "Оборудование → Контроллер: выберите порт"
    }

private fun dashboardScannerPortHint(assignments: Map<String, PortRole>): String {
    val path = assignments.entries.firstOrNull { it.value == PortRole.SCANNER }?.key
    return if (path != null) {
        "Порт с ролью SCANNER: ${path.takeLast(32)}"
    } else {
        "Роль SCANNER не назначена — см. сканер в сервисном меню"
    }
}

private fun dashboardPaymentOk(terminalLine: String): Boolean =
    terminalLine.isNotBlank() &&
        !terminalLine.contains("Таймаут") &&
        !terminalLine.contains("Отклонено") &&
        !terminalLine.contains("Отменено") &&
        !terminalLine.contains("Сессия отменена")

private fun dashboardRfidHint(
    path: String,
    connected: Boolean,
): String =
    when {
        connected && path.isNotBlank() -> path.takeLast(40)
        path.isNotBlank() -> "Порт: ${path.takeLast(36)} — нажмите «Подключить» (RFID)"
        else -> "Оборудование → RFID: выберите порт"
    }

@Composable
private fun StatusPill(
    label: String,
    ok: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (ok) scheme.primaryContainer else scheme.errorContainer.copy(alpha = 0.7f),
    ) {
        Text(
            "$label ${if (ok) "✓" else "·"}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (ok) scheme.onPrimaryContainer else scheme.onErrorContainer,
        )
    }
}

@Composable
private fun CompactDashboardCell(
    cell: ServiceDashboardCellUi,
    modifier: Modifier = Modifier,
    onFillToFull: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        if (!cell.hasData) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${cell.cellNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.primary,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(
                        "—",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        lineHeight = serviceMenuSp(16),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            return@Card
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .weight(0.65f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            ) {
                DashboardCellTasteImage(
                    mediaKey = cell.tasteMediaKey,
                    hexFromCatalog = cell.tasteHexColor,
                    titleForInitial = cell.catalogTitle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = scheme.outline.copy(alpha = 0.22f),
            )
            Column(
                modifier =
                    Modifier
                        .weight(0.35f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${cell.cellNumber}",
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.primary,
                            modifier = Modifier.widthIn(min = 18.dp),
                        )
                        Text(
                            cell.catalogTitle,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            lineHeight = serviceMenuSp(18),
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val pct = (cell.fillFraction * 100f).toInt().coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = { cell.fillFraction },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        color = scheme.primary,
                        trackColor = scheme.surfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "$pct%",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "${cell.volumeMl}/${cell.maxVolumeMl}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        buildString {
                            append("%.2f".format(cell.ingredientLiters))
                            append(" л")
                            cell.approxDrinks?.let { d ->
                                append(" · ~")
                                append(d)
                                append(" пор.")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        lineHeight = serviceMenuSp(18),
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val maxMl = cell.maxVolumeMl
                if (maxMl > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { onFillToFull(maxMl) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                            ),
                    ) {
                        Text("До макс.", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCellTasteImage(
    mediaKey: String?,
    hexFromCatalog: String?,
    titleForInitial: String,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val hex =
        (mediaKey?.let { DashboardMediaKeyHexOverride[it] } ?: hexFromCatalog) ?: "#5C6BC0"
    val accent =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrDefault(MaterialTheme.colorScheme.primary)
    val imageUri = remember(mediaKey) { ViwaElectronAssets.horizontalCardImageUri(mediaKey) }
    val initial =
        titleForInitial.trim().take(1).uppercase(Locale.getDefault()).ifEmpty { "?" }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(ctx).data(imageUri).crossfade(300).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Success ->
                        SubcomposeAsyncImageContent(
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    else ->
                        DashboardTasteImagePlaceholder(
                            accent = accent,
                            initial = initial,
                        )
                }
            }
        } else {
            DashboardTasteImagePlaceholder(
                accent = accent,
                initial = initial,
            )
        }
    }
}

@Composable
private fun DashboardTasteImagePlaceholder(
    accent: Color,
    initial: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.38f), accent.copy(alpha = 0.12f)),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = serviceMenuSp(22),
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}
