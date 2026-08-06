package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.domain.model.CellVolumeStatus
import com.viwa.android.domain.model.MvpInventoryContentUpdate
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.screens.service.SettingsColumn
import com.viwa.android.ui.screens.service.SettingsTextField

/**
 * Legacy MVP inventory editor (product + prices). **Not mounted** in [com.viwa.android.ui.screens.service.ViwaServiceMenuTabContent];
 * dashboard PATCH is source of truth for content/prices. Use Обслуживание → Остатки for operator taste override.
 */
@Composable
fun ViwaTelemetryInventoryTab(
    viewModel: ServiceViewModel,
) {
    MvpTelemetryInventoryTab(viewModel = viewModel)
}

@Composable
private fun MvpTelemetryInventoryTab(
    viewModel: ServiceViewModel,
) {
    val rows by viewModel.mvpInventoryRows.collectAsStateWithLifecycle()
    val products by viewModel.snapshotProducts.collectAsStateWithLifecycle()
    val draftProducts = remember { mutableStateMapOf<String, String?>() }
    val draftPrice300 = remember { mutableStateMapOf<String, String>() }
    val draftPrice700 = remember { mutableStateMapOf<String, String>() }
    var banner by remember { mutableStateOf<String?>(null) }
    var productPickerCellUuid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshInventoryRows()
    }

    LaunchedEffect(rows) {
        rows.forEach { row ->
            if (!draftProducts.containsKey(row.uuid)) {
                draftProducts[row.uuid] = row.productUuid
            }
            if (!draftPrice300.containsKey(row.uuid)) {
                draftPrice300[row.uuid] = row.price300Kopecks?.let { it / 100 }?.toString().orEmpty()
            }
            if (!draftPrice700.containsKey(row.uuid)) {
                draftPrice700[row.uuid] = row.price700Kopecks?.let { it / 100 }?.toString().orEmpty()
            }
        }
    }

    SettingsColumn {
        Text(
            "Legacy (не в меню): продукт/цены редактируются на dashboard. " +
                "Локальное сохранение здесь — best-effort uplink без server ack; не заменяет dashboard PATCH.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "MVP: продукты из локального snapshot.products[] (не REST). Сохранение → cells.content.report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { viewModel.refreshInventoryRows() }) {
            Text("Обновить snapshot")
        }
        Spacer(Modifier.height(16.dp))
        if (rows.isEmpty()) {
            Text(
                "Нет snapshot ячеек. Дождитесь cells.snapshot по MVP WS.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Ячеек: ${rows.size}, продуктов в каталоге: ${products.size}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    MvpInventoryEditRow(
                        row = row,
                        products = products,
                        selectedProductUuid = draftProducts[row.uuid],
                        price300Rub = draftPrice300[row.uuid].orEmpty(),
                        price700Rub = draftPrice700[row.uuid].orEmpty(),
                        onPickProduct = { productPickerCellUuid = row.uuid },
                        onPrice300Change = { draftPrice300[row.uuid] = it },
                        onPrice700Change = { draftPrice700[row.uuid] = it },
                        onSave = {
                            val p300 = draftPrice300[row.uuid]?.trim()?.toIntOrNull()
                            val p700 = draftPrice700[row.uuid]?.trim()?.toIntOrNull()
                            if (p300 != null && p300 < 0 || p700 != null && p700 < 0) {
                                banner = "Цены должны быть ≥ 0"
                                return@MvpInventoryEditRow
                            }
                            viewModel.saveMvpInventoryContent(
                                listOf(
                                    MvpInventoryContentUpdate(
                                        cellUuid = row.uuid,
                                        productUuid = draftProducts[row.uuid],
                                        dosage1PriceKopecks = p300?.times(100),
                                        dosage2PriceKopecks = p700?.times(100),
                                    ),
                                ),
                            ) { ok, msg ->
                                banner = msg
                                if (ok) {
                                    viewModel.refreshInventoryRows()
                                }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            banner?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    val pickerUuid = productPickerCellUuid
    if (pickerUuid != null) {
        AlertDialog(
            onDismissRequest = { productPickerCellUuid = null },
            title = { Text("Выбор продукта") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            draftProducts[pickerUuid] = null
                            productPickerCellUuid = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("— без продукта —")
                    }
                    products.forEach { product ->
                        TextButton(
                            onClick = {
                                draftProducts[pickerUuid] = product.uuid
                                productPickerCellUuid = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${product.name} (${product.tasteMediaKey})")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { productPickerCellUuid = null }) {
                    Text("Закрыть")
                }
            },
        )
    }
}

@Composable
private fun MvpInventoryEditRow(
    row: MvpInventoryTableRow,
    products: List<TelemetryProduct>,
    selectedProductUuid: String?,
    price300Rub: String,
    price700Rub: String,
    onPickProduct: () -> Unit,
    onPrice300Change: (String) -> Unit,
    onPrice700Change: (String) -> Unit,
    onSave: () -> Unit,
) {
    val productLabel =
        selectedProductUuid?.let { id -> products.find { it.uuid == id }?.name } ?: "— без продукта —"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "Яч.${row.cellNumber}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(56.dp),
            )
            Text(
                volumeStatusLabel(row.volumeStatus),
                color = volumeStatusColor(row.volumeStatus),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(56.dp),
            )
            Text(
                "ост.${row.volumeMl} мл",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = productLabel,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onPickProduct).padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth()) {
            SettingsTextField(
                label = "300 мл, ₽",
                value = price300Rub,
                onValueChange = onPrice300Change,
                modifier = Modifier.weight(1f),
                fieldKey = "mvp_price300_${row.uuid}",
                maxLength = 8,
            )
            Spacer(Modifier.width(8.dp))
            SettingsTextField(
                label = "700 мл, ₽",
                value = price700Rub,
                onValueChange = onPrice700Change,
                modifier = Modifier.weight(1f),
                fieldKey = "mvp_price700_${row.uuid}",
                maxLength = 8,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onSave) {
                Text("Сохранить")
            }
        }
    }
}

@Composable
private fun HCell(
    text: String,
    widthDp: Int,
    maxLines: Int = 2,
) {
    Text(
        text = text,
        modifier = Modifier.width(widthDp.dp),
        style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun volumeStatusLabel(status: CellVolumeStatus): String =
    when (status) {
        CellVolumeStatus.STOP -> "стоп"
        CellVolumeStatus.WARNING -> "мало"
        CellVolumeStatus.NORMAL -> "норма"
    }

@Composable
private fun volumeStatusColor(status: CellVolumeStatus): Color =
    when (status) {
        CellVolumeStatus.STOP -> MaterialTheme.colorScheme.error
        CellVolumeStatus.WARNING -> Color(0xFFB8860B)
        CellVolumeStatus.NORMAL -> MaterialTheme.colorScheme.primary
    }
