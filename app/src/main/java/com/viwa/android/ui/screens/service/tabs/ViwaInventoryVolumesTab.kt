package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viwa.android.domain.inventory.InventoryCellRecipeSupport
import com.viwa.android.data.local.recipe.RecipeSyncFeatureFlags
import com.viwa.android.domain.inventory.InventoryManagedRecipeSupport
import com.viwa.android.domain.recipe.RecipeDriftBadge
import com.viwa.android.domain.inventory.InventoryCellTasteChange
import com.viwa.android.domain.inventory.InventoryPriceFormat
import com.viwa.android.domain.inventory.InventoryTastePickerSupport
import com.viwa.android.domain.inventory.InventoryVolumeDraftMerge
import com.viwa.android.domain.model.CellVolumeStatus
import com.viwa.android.domain.model.CellVolumeUpdate
import com.viwa.android.domain.model.MvpInventoryTableRow
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.ui.screens.service.ServiceMenuTestTags
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.screens.service.SettingsColumn
import com.viwa.android.ui.screens.service.SettingsTextField

@Composable
fun ViwaInventoryVolumesTab(
    viewModel: ServiceViewModel,
) {
    val rows by viewModel.mvpInventoryRows.collectAsStateWithLifecycle()
    val products by viewModel.snapshotProducts.collectAsStateWithLifecycle()
    val serviceState by viewModel.state.collectAsStateWithLifecycle()
    val recipeSyncRevision by viewModel.recipeSyncUiRevision.collectAsStateWithLifecycle()
    var draftVolumes by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var dirtyVolumeCells by remember { mutableStateOf(setOf<Int>()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerIsError by remember { mutableStateOf(false) }
    var syrupPrimeBusyCell by remember { mutableStateOf<Int?>(null) }
    var recipeRow by remember { mutableStateOf<MvpInventoryTableRow?>(null) }
    var tastePickerRow by remember { mutableStateOf<MvpInventoryTableRow?>(null) }
    var tasteConfirm by remember { mutableStateOf<TasteChangeConfirmation?>(null) }
    var tasteChangeBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshInventoryRows()
    }

    LaunchedEffect(rows) {
        if (rows.isNotEmpty()) {
            draftVolumes =
                InventoryVolumeDraftMerge.mergeRowsIntoDraft(
                    rows = rows,
                    currentDraft = draftVolumes,
                    dirtyCellNumbers = dirtyVolumeCells,
                )
        }
    }

    SettingsColumn(modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_ROOT)) {
        Text(
            "Объёмы из telemetryCellsSnapshot. Сохранение → локальный snapshot и cells.volume.report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                banner = null
                bannerIsError = false
                viewModel.refreshInventoryRows()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Обновить snapshot")
        }
        Spacer(Modifier.height(16.dp))
        if (rows.isEmpty()) {
            Text(
                "Нет snapshot ячеек. Подключите MVP WS и дождитесь cells.snapshot.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    val isSyrupCell =
                        serviceState.syrupContainers.any { it.containerNumber == row.cellNumber }
                    MvpVolumeRow(
                        row = row,
                        text = draftVolumes[row.cellNumber].orEmpty(),
                        syrupPrimeEnabled = isSyrupCell,
                        syrupPrimeBusy = syrupPrimeBusyCell == row.cellNumber,
                        recipeEnabled = InventoryCellRecipeSupport.isRecipeApplicable(row),
                        tasteChangeEnabled = InventoryCellTasteChange.isTasteChangeApplicable(row),
                        onTextChange = { t ->
                            draftVolumes = draftVolumes + (row.cellNumber to t)
                            dirtyVolumeCells = dirtyVolumeCells + row.cellNumber
                            banner = null
                            bannerIsError = false
                        },
                        onFillToMax = {
                            banner = null
                            bannerIsError = false
                            viewModel.fillInventoryCellToMax(row.cellNumber, row.maxVolume) { ok, msg ->
                                banner = msg
                                bannerIsError = !ok
                                if (ok) {
                                    viewModel.refreshInventoryRows()
                                }
                            }
                        },
                        onSyrupPrime =
                            if (isSyrupCell) {
                                {
                                    banner = null
                                    bannerIsError = false
                                    syrupPrimeBusyCell = row.cellNumber
                                    viewModel.runInventorySyrupPrime(row.cellNumber) { ok, msg ->
                                        banner = msg
                                        bannerIsError = !ok
                                        syrupPrimeBusyCell = null
                                    }
                                }
                            } else {
                                null
                            },
                        onShowRecipe = { recipeRow = row },
                        onChangeTaste = { tastePickerRow = row },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val updates = buildMvpUpdates(rows, draftVolumes)
                    if (updates == null) {
                        banner = "Введите целые числа ≥ 0 для всех ячеек"
                        bannerIsError = true
                    } else {
                        viewModel.saveInventoryVolumes(updates) { ok, msg ->
                            banner = msg
                            bannerIsError = !ok
                            if (ok) {
                                viewModel.refreshInventoryRows()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сохранить")
            }
            banner?.let { b ->
                Spacer(Modifier.height(8.dp))
                Text(
                    b,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (bannerIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }

    recipeRow?.let { row ->
        ManagedInventoryRecipeDialog(
            row = row,
            viewModel = viewModel,
            recipeSyncRevision = recipeSyncRevision,
            onDismiss = { recipeRow = null },
            onBanner = { message, isError ->
                banner = message
                bannerIsError = isError
            },
        )
    }

    tastePickerRow?.let { row ->
        InventoryTastePickerDialog(
            row = row,
            products = products,
            onDismiss = { tastePickerRow = null },
            onProductSelected = { product ->
                tastePickerRow = null
                tasteConfirm = TasteChangeConfirmation(row = row, product = product)
            },
        )
    }

    tasteConfirm?.let { pending ->
        InventoryTasteConfirmDialog(
            pending = pending,
            busy = tasteChangeBusy,
            onDismiss = {
                if (!tasteChangeBusy) {
                    tasteConfirm = null
                }
            },
            onConfirm = {
                tasteChangeBusy = true
                viewModel.changeInventoryCellTaste(pending.row.uuid, pending.product.uuid) { ok, msg ->
                    tasteChangeBusy = false
                    tasteConfirm = null
                    banner = msg
                    bannerIsError = !ok
                }
            },
        )
    }
}

private data class TasteChangeConfirmation(
    val row: MvpInventoryTableRow,
    val product: TelemetryProduct,
)

@Composable
private fun ManagedInventoryRecipeDialog(
    row: MvpInventoryTableRow,
    viewModel: ServiceViewModel,
    recipeSyncRevision: Int,
    onDismiss: () -> Unit,
    onBanner: (message: String, isError: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var panel by remember(row.uuid) { mutableStateOf<InventoryManagedRecipeSupport.InventoryRecipePanel?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var editDraft by remember { mutableStateOf(InventoryManagedRecipeSupport.EditDraft("", "", "")) }
    var editError by remember { mutableStateOf<String?>(null) }
    var showEditConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var dialogBanner by remember { mutableStateOf<String?>(null) }
    var dialogBannerError by remember { mutableStateOf(false) }

    LaunchedEffect(row.uuid, recipeSyncRevision) {
        panel = viewModel.buildInventoryRecipePanel(row)
    }

    val currentPanel = panel
    if (currentPanel == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Рецепт · ячейка ${row.cellNumber}") },
            text = { Text("Загрузка…") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            },
        )
        return
    }

    if (!RecipeSyncFeatureFlags.FEATURE_RECIPE_SYNC) {
        LegacyInventoryRecipeDialog(row = row, onDismiss = onDismiss)
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Рецепт · ячейка ${row.cellNumber}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(ServiceMenuTestTags.INVENTORY_RECIPE_DIALOG),
            ) {
                Text(row.productName ?: "—", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                currentPanel.driftBadge?.let { badge ->
                    Text(
                        InventoryManagedRecipeSupport.driftBadgeLabel(badge) ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = driftBadgeColor(badge),
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_DRIFT_BADGE),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                currentPanel.baseVersionLabel?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_BASE_VERSION),
                    )
                }
                currentPanel.syncStatusLabel?.let { sync ->
                    Text(
                        sync,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_SYNC_STATUS),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val effective = currentPanel.effective
                if (effective != null && effective.isRecipeComplete) {
                    Text("Эффективный рецепт", style = MaterialTheme.typography.labelLarge)
                    Text(
                        InventoryManagedRecipeSupport.formatEffectiveLine(effective, 300, currentPanel.conversionFactor),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        InventoryManagedRecipeSupport.formatEffectiveLine(effective, 700, currentPanel.conversionFactor),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Эффективный рецепт не инициализирован",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                currentPanel.assignmentBase?.let { base ->
                    InventoryManagedRecipeSupport.formatBaseTripleLine(base)?.let { baseLine ->
                        Spacer(Modifier.height(8.dp))
                        Text("База продукта", style = MaterialTheme.typography.labelLarge)
                        Text(baseLine, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "CF=${"%.4f".format(currentPanel.conversionFactor)} (отдельно от drift)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (editMode) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Редактирование (integer/deci-ml)", style = MaterialTheme.typography.labelMedium)
                    SettingsTextField(
                        label = "baseDrinkVolumeMl",
                        value = editDraft.baseDrinkVolumeMl,
                        onValueChange = { editDraft = editDraft.copy(baseDrinkVolumeMl = it) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.fillMaxWidth(),
                        fieldKey = "recipe_edit_base_${row.cellNumber}",
                    )
                    SettingsTextField(
                        label = "waterDeciMl",
                        value = editDraft.waterDeciMl,
                        onValueChange = { editDraft = editDraft.copy(waterDeciMl = it) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.fillMaxWidth(),
                        fieldKey = "recipe_edit_water_${row.cellNumber}",
                    )
                    SettingsTextField(
                        label = "productDeciMl",
                        value = editDraft.productDeciMl,
                        onValueChange = { editDraft = editDraft.copy(productDeciMl = it) },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.fillMaxWidth(),
                        fieldKey = "recipe_edit_product_${row.cellNumber}",
                    )
                    editError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                dialogBanner?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        color = if (dialogBannerError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editMode) {
                    Button(
                        onClick = {
                            val validation = InventoryManagedRecipeSupport.validateEditDraft(editDraft)
                            if (!validation.valid) {
                                editError = validation.errorMessage
                            } else {
                                editError = null
                                showEditConfirm = true
                            }
                        },
                        enabled = currentPanel.canEdit,
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_EDIT_SAVE),
                    ) {
                        Text("Сохранить")
                    }
                    OutlinedButton(onClick = { editMode = false }) {
                        Text("Отмена")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            val triple = currentPanel.effective?.triple
                            editDraft =
                                if (triple != null) {
                                    InventoryManagedRecipeSupport.EditDraft(
                                        baseDrinkVolumeMl = triple.baseDrinkVolumeMl.toString(),
                                        waterDeciMl = triple.waterDeciMl.toString(),
                                        productDeciMl = triple.productDeciMl.toString(),
                                    )
                                } else {
                                    InventoryManagedRecipeSupport.EditDraft("", "", "")
                                }
                            editMode = true
                        },
                        enabled = currentPanel.canEdit,
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_EDIT_BUTTON),
                    ) {
                        Text("Изменить")
                    }
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        enabled = currentPanel.canReset,
                        modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_RESET_BUTTON),
                    ) {
                        Text("Сброс")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )

    if (showEditConfirm) {
        AlertDialog(
            onDismissRequest = { showEditConfirm = false },
            title = { Text("Подтвердить изменение рецепта") },
            text = {
                Column(Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_EDIT_CONFIRM)) {
                    Text("Ячейка ${row.cellNumber} · ${row.productName ?: "—"}")
                    Text("Будет сохранено локально и отправлено на сервер при подключении.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditConfirm = false
                        val validation = InventoryManagedRecipeSupport.validateEditDraft(editDraft)
                        val triple = validation.triple ?: return@Button
                        viewModel.saveInventoryRecipeEdit(row, triple) { ok, msg ->
                            dialogBanner = msg
                            dialogBannerError = !ok
                            onBanner(msg, !ok)
                            if (ok) {
                                editMode = false
                                scope.launch { panel = viewModel.buildInventoryRecipePanel(row) }
                            }
                        }
                    },
                    modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_EDIT_CONFIRM_BUTTON),
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditConfirm = false }) { Text("Отмена") }
            },
        )
    }

    if (showResetConfirm) {
        val base = currentPanel.assignmentBase
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Сброс к базе продукта") },
            text = {
                Text(
                    base?.let { InventoryManagedRecipeSupport.resetConfirmationMessage(it) }
                        ?: "Сбросить к базе?",
                    modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_RESET_CONFIRM),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetInventoryRecipeToBase(row) { ok, msg ->
                            dialogBanner = msg
                            dialogBannerError = !ok
                            onBanner(msg, !ok)
                            if (ok) {
                                scope.launch { panel = viewModel.buildInventoryRecipePanel(row) }
                            }
                        }
                    },
                    modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_RECIPE_RESET_CONFIRM_BUTTON),
                ) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun LegacyInventoryRecipeDialog(
    row: MvpInventoryTableRow,
    onDismiss: () -> Unit,
) {
    val basis = InventoryCellRecipeSupport.basisForRow(row)
    val line300 = InventoryCellRecipeSupport.volumeRecipeLine(basis, 300)
    val line700 = InventoryCellRecipeSupport.volumeRecipeLine(basis, 700)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Рецепт · ячейка ${row.cellNumber}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(ServiceMenuTestTags.INVENTORY_RECIPE_DIALOG),
            ) {
                Text(row.productName ?: "—", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    InventoryCellRecipeSupport.SOURCE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(InventoryCellRecipeSupport.formatBasis(basis), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(InventoryCellRecipeSupport.formatVolumeLine(line300), style = MaterialTheme.typography.bodyMedium)
                Text(InventoryCellRecipeSupport.formatVolumeLine(line700), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun driftBadgeColor(badge: RecipeDriftBadge): Color =
    when (badge) {
        RecipeDriftBadge.ALIGNED -> MaterialTheme.colorScheme.primary
        RecipeDriftBadge.MODIFIED -> Color(0xFFB8860B)
        RecipeDriftBadge.BASE_UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        RecipeDriftBadge.OFFLINE_STALE -> MaterialTheme.colorScheme.error
    }

@Composable
private fun InventoryTastePickerDialog(
    row: MvpInventoryTableRow,
    products: List<TelemetryProduct>,
    onDismiss: () -> Unit,
    onProductSelected: (TelemetryProduct) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сменить вкус · ячейка ${row.cellNumber}") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(ServiceMenuTestTags.INVENTORY_TASTE_PICKER),
            ) {
                if (products.isEmpty()) {
                    Text(
                        "Нет продуктов в snapshot.products.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    products.forEach { product ->
                        val isCurrent =
                            InventoryTastePickerSupport.isCurrentProduct(row.productUuid, product.uuid)
                        TextButton(
                            onClick = { onProductSelected(product) },
                            enabled = !isCurrent,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag(ServiceMenuTestTags.inventoryTasteOptionTag(product.uuid)),
                        ) {
                            Text(InventoryTastePickerSupport.optionLabel(product, isCurrent))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun InventoryTasteConfirmDialog(
    pending: TasteChangeConfirmation,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val currentLabel = pending.row.productName ?: "— без продукта —"
    val price300 = InventoryPriceFormat.formatKopecks(pending.row.price300Kopecks)
    val price700 = InventoryPriceFormat.formatKopecks(pending.row.price700Kopecks)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердить смену вкуса") },
        text = {
            Column(Modifier.testTag(ServiceMenuTestTags.INVENTORY_TASTE_CONFIRM)) {
                Text("Ячейка ${pending.row.cellNumber}")
                Text("Было: $currentLabel")
                Text("Станет: ${pending.product.name} (${pending.product.tasteMediaKey})")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Цены сохранятся: 300 мл = $price300 ₽, 700 мл = $price700 ₽",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag(ServiceMenuTestTags.INVENTORY_TASTE_CONFIRM_BUTTON),
            ) {
                Text(if (busy) "Отправка..." else "Подтвердить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun MvpVolumeRow(
    row: MvpInventoryTableRow,
    text: String,
    syrupPrimeEnabled: Boolean,
    syrupPrimeBusy: Boolean,
    recipeEnabled: Boolean,
    tasteChangeEnabled: Boolean,
    onTextChange: (String) -> Unit,
    onFillToMax: () -> Unit,
    onSyrupPrime: (() -> Unit)?,
    onShowRecipe: () -> Unit,
    onChangeTaste: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ячейка ${row.cellNumber} · ${row.productName ?: "—"}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                volumeStatusLabel(row.volumeStatus),
                color = volumeStatusColor(row.volumeStatus),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            "block=${row.blockVolume} sos=${row.sosVolume} max=${row.maxVolume} мл",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        VolumeRowControls(
            cellNumber = row.cellNumber,
            text = text,
            syrupPrimeEnabled = syrupPrimeEnabled,
            syrupPrimeBusy = syrupPrimeBusy,
            onTextChange = onTextChange,
            onFillToMax = onFillToMax,
            onSyrupPrime = onSyrupPrime,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onShowRecipe,
                enabled = recipeEnabled,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(ServiceMenuTestTags.inventoryRecipeButtonTag(row.cellNumber)),
            ) {
                Text("Рецепт")
            }
            OutlinedButton(
                onClick = onChangeTaste,
                enabled = tasteChangeEnabled,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(ServiceMenuTestTags.inventoryChangeTasteButtonTag(row.cellNumber)),
            ) {
                Text("Сменить вкус")
            }
        }
    }
}

@Composable
private fun VolumeRowControls(
    cellNumber: Int,
    text: String,
    syrupPrimeEnabled: Boolean,
    syrupPrimeBusy: Boolean,
    onTextChange: (String) -> Unit,
    onFillToMax: (() -> Unit)?,
    onSyrupPrime: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTextField(
            label = "Объём, мл",
            value = text,
            onValueChange = onTextChange,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            fieldKey = "inventory_volume_$cellNumber",
            maxLength = 12,
        )
        if (onFillToMax != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onFillToMax) {
                Text("До полного")
            }
        }
        if (syrupPrimeEnabled && onSyrupPrime != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onSyrupPrime,
                enabled = !syrupPrimeBusy,
            ) {
                Text(if (syrupPrimeBusy) "Прокачка..." else "Прокачка 30 мл")
            }
        }
    }
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

private fun buildMvpUpdates(
    rows: List<MvpInventoryTableRow>,
    draft: Map<Int, String>,
): List<CellVolumeUpdate>? {
    val out = ArrayList<CellVolumeUpdate>(rows.size)
    for (r in rows) {
        val s = draft[r.cellNumber]?.trim().orEmpty()
        val v = s.toIntOrNull() ?: return null
        if (v < 0) return null
        out.add(CellVolumeUpdate(containerNumber = r.cellNumber, volumeMl = v))
    }
    return out
}
