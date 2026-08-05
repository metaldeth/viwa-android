package com.viwa.android.ui.screens.service

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.viwa.android.data.remote.telemetry.mvp.SerialNumberUtils
import com.viwa.android.ui.screens.service.devices.ViwaDevicesTab
import com.viwa.android.ui.screens.service.tabs.ViwaAppearanceThemeSection
import com.viwa.android.ui.screens.service.tabs.ViwaControllerDebugTab
import com.viwa.android.ui.screens.service.tabs.ViwaIdleTab
import com.viwa.android.ui.screens.service.tabs.ViwaInventoryVolumesTab
import com.viwa.android.ui.screens.service.tabs.ViwaPreparingTimeTab
import com.viwa.android.ui.screens.service.tabs.ViwaServiceDashboardTab
import com.viwa.android.ui.screens.service.tabs.ViwaSubscriptionDebugTab
import com.viwa.android.ui.screens.service.tabs.ViwaSyrupCalibrationTab
import com.viwa.android.ui.screens.service.tabs.ViwaTelemetryWsLogTab
import com.viwa.android.ui.screens.service.tabs.ViwaWaterCalibrationTab

@Composable
fun ViwaServiceMenuTabContent(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    when (state.selectedServiceGroupId) {
        ViwaServiceGroupId.Dashboard ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.DashboardOverview -> ViwaServiceDashboardTab(viewModel)
                else -> ViwaServiceDashboardTab(viewModel)
            }

        ViwaServiceGroupId.Telemetry ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.TelemetryConnection -> ViwaTelemetryConnectionTab(state, viewModel)
                ViwaServiceSubTabId.TelemetryAddresses -> ViwaTelemetryAddressesTab(state, viewModel)
                else -> ViwaTelemetryConnectionTab(state, viewModel)
            }

        ViwaServiceGroupId.Debug ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.DebugWsLogs -> ViwaTelemetryWsLogTab(viewModel)
                ViwaServiceSubTabId.DebugController -> ViwaControllerDebugTab(state, viewModel)
                ViwaServiceSubTabId.DebugSubscription -> ViwaSubscriptionDebugTab(state, viewModel)
                else -> ViwaTelemetryWsLogTab(viewModel)
            }

        ViwaServiceGroupId.Integrations ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.Sbp -> ViwaSbpIntegrationTab(state, viewModel)
                ViwaServiceSubTabId.Nanokassa -> ViwaNanoIntegrationTab(state, viewModel)
                ViwaServiceSubTabId.Max -> ViwaMaxIntegrationTab(state, viewModel)
                else -> ViwaSbpIntegrationTab(state, viewModel)
            }

        ViwaServiceGroupId.Equipment -> ViwaDevicesTab()

        ViwaServiceGroupId.Maintenance ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.Inventory ->
                    ViwaInventoryVolumesTab(viewModel)
                ViwaServiceSubTabId.CalibrationSyrup ->
                    ViwaSyrupCalibrationTab(viewModel)
                ViwaServiceSubTabId.CalibrationWater ->
                    ViwaWaterCalibrationTab(viewModel)
                ViwaServiceSubTabId.PreparingTime ->
                    ViwaPreparingTimeTab(viewModel)
                else ->
 ViwaPlaceholderTab("Обслуживание — см. ServiceMenu.")
            }

        ViwaServiceGroupId.Settings ->
            when (state.selectedServiceSubTabId) {
                ViwaServiceSubTabId.DevMode -> ViwaSettingsDevModeTab(state, viewModel)
                ViwaServiceSubTabId.Idle -> ViwaIdleTab()
                ViwaServiceSubTabId.Window -> ViwaWindowTab()
                ViwaServiceSubTabId.Theme -> ViwaThemeTab(state, viewModel)
                else -> ViwaSettingsDevModeTab(state, viewModel)
            }

        ViwaServiceGroupId.CardPayment,
        ViwaServiceGroupId.Updater,
        ViwaServiceGroupId.Performance,
        ViwaServiceGroupId.Metrics ->
            ViwaPlaceholderTab("Раздел недоступен в меню.")
    }
}

@Composable
private fun ViwaThemeTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        ViwaAppearanceThemeSection(state = state, viewModel = viewModel)
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        ViwaUpdaterSection(state = state, viewModel = viewModel, embedded = true)
    }
}

@Composable
private fun ViwaPlaceholderTab(message: String) {
    SettingsColumn {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ViwaTelemetryConnectionTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    DisposableEffect(Unit) {
        viewModel.onTelemetryConnectionTabVisible()
        onDispose { viewModel.onTelemetryConnectionTabHidden() }
    }
    val conn by viewModel.telemetryConnectionUi.collectAsStateWithLifecycle()
    SettingsColumn(
        modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_CONNECTION_ROOT),
    ) {
        val connected = conn.connected
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ServiceMenuTestTags.TELEMETRY_CONNECTION_STATUS_CARD),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (connected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val contentColor =
                        if (connected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    Text("WebSocket телеметрии", color = contentColor, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (connected) "Подключено" else "Не подключено",
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_CONNECTION_STATUS_TEXT),
                    )
                    Text(
                        conn.label,
                        color = contentColor.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    conn.error?.let { err ->
                        Text(
                            err,
                            color = contentColor.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(
                    onClick = { viewModel.reconnectTelemetry() },
                    enabled = !state.telemetryBusy,
                    modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_CONNECTION_RECONNECT),
                ) {
                    Text("Реконнект")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Регистрация и подключение", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ключ REG-… из веб-панели (или QR) и серийный номер. После регистрации — WebSocket по JWT.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        state.telemetryQrScannedBanner?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_QR_SCANNED_BANNER),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
        }
        SettingsTextField(
            label = "Ключ регистрации (REG-…)",
            value = state.telemetryRegKey,
            onValueChange = viewModel::setTelemetryRegKey,
            testTag = ServiceMenuTestTags.TELEMETRY_REG_KEY_INPUT,
        )
        SettingsTextField(
            label = "Серийный номер (serialNumber)",
            value = state.telemetrySerial,
            onValueChange = viewModel::setTelemetrySerial,
            testTag = ServiceMenuTestTags.TELEMETRY_SERIAL_INPUT,
        )
        if (viewModel.telemetrySerialMismatch(state)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "В поле: ${SerialNumberUtils.normalize(state.telemetrySerial)} · " +
                    "сохранён: ${SerialNumberUtils.normalize(state.telemetryPersistedSerial)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.telemetrySerialNeedsRegistration || !state.telemetryEnrolled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Требуется регистрация для серийного номера " +
                    SerialNumberUtils.normalize(state.telemetrySerial).ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (viewModel.isTelemetryFreeSerialUiVisible()) {
            OutlinedButton(
                onClick = { viewModel.requestTelemetryFreeSerial() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ServiceMenuTestTags.TELEMETRY_RESERVE_SERIAL),
                enabled = !state.telemetryBusy,
            ) {
                Text("Запросить свободный serial")
            }
        }
        if (state.telemetrySerialConflict || state.telemetryRebindConfirmVisible) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ServiceMenuTestTags.TELEMETRY_REBIND_CARD),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Serial уже привязан к другой плате",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Подтвердите перепривязку — старый credential на прежней плате перестанет работать.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { viewModel.dismissTelemetryRebindConfirm() },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .testTag(ServiceMenuTestTags.TELEMETRY_REBIND_CANCEL),
                        ) {
                            Text("Отмена")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.confirmTelemetryRebindAndRegister() },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .testTag(ServiceMenuTestTags.TELEMETRY_REBIND_CONFIRM),
                            enabled = !state.telemetryBusy,
                        ) {
                            Text("Перепривязать")
                        }
                    }
                }
            }
        }
        if (state.telemetryEnrolled && !state.telemetrySerialNeedsRegistration) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Автомат уже зарегистрирован — для повторного подключения используйте «Подключить WS».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(
            onClick = { viewModel.provisionTelemetryMachine() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ServiceMenuTestTags.TELEMETRY_AUTO_PROVISION),
            enabled = !state.telemetryBusy && !state.telemetryEnrolled,
        ) {
            Text(
                if (state.telemetryEnrolled) {
                    "Уже зарегистрирован"
                } else {
                    "Авторегистрация"
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            if (state.telemetryEnrolled && !state.telemetrySerialNeedsRegistration) {
                OutlinedButton(
                    onClick = { viewModel.registerTelemetryMachine() },
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag(ServiceMenuTestTags.TELEMETRY_REGISTER),
                    enabled = false,
                ) {
                    Text("Регистрация")
                }
            } else {
                Button(
                    onClick = { viewModel.registerTelemetryMachine() },
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag(ServiceMenuTestTags.TELEMETRY_REGISTER),
                    enabled =
                        !state.telemetryBusy &&
                            state.telemetrySerial.isNotBlank() &&
                            state.telemetryRegKey.isNotBlank(),
                ) {
                    Text("Регистрация")
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.connectTelemetry() },
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(ServiceMenuTestTags.TELEMETRY_CONNECT_WS),
                enabled = viewModel.telemetryCanConnect(state),
            ) {
                Text("Подключить WS")
            }
        }
        if (state.telemetryBusy) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ServiceMenuTestTags.TELEMETRY_BUSY),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Запрос к серверу…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = { viewModel.disconnectTelemetry() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ServiceMenuTestTags.TELEMETRY_DISCONNECT_WS),
            enabled = !state.telemetryBusy,
        ) {
            Text("Отключить WS")
        }
        ViwaTelemetryBanner(state)
    }
}

@Composable
private fun ViwaTelemetryAddressesTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn(
        modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_ADDRESSES_ROOT),
    ) {
        Text("Адреса телеметрии", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "REG-ключ + serial → JWT WebSocket. URL API и WS задаются ниже.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SettingsTextField(
            label = "API (HTTPS) — регистрация машины",
            value = state.telemetryApiUrl,
            onValueChange = viewModel::setTelemetryApiUrl,
            testTag = ServiceMenuTestTags.TELEMETRY_API_URL_INPUT,
        )
        SettingsTextField(
            label = "WebSocket URL",
            value = state.telemetryWsUrl,
            onValueChange = viewModel::setTelemetryWsUrl,
            testTag = ServiceMenuTestTags.TELEMETRY_WS_URL_INPUT,
        )
        Button(
            onClick = { viewModel.saveTelemetryEndpoints() },
            enabled = !state.telemetryBusy,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ServiceMenuTestTags.TELEMETRY_SAVE_ADDRESSES),
        ) {
            Text("Сохранить адреса")
        }
        ViwaTelemetryBanner(state)
    }
}

@Composable
private fun ViwaTelemetryTestsTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        Text("Тестовые запросы", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Legacy Shaker topic-запросы удалены. Данные ячеек приходят по MVP WS (cells.snapshot после hello).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ViwaTelemetryBanner(state)
    }
}

@Composable
private fun ViwaTelemetryBanner(state: ServiceUiState) {
    state.telemetryBanner?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(ServiceMenuTestTags.TELEMETRY_BANNER),
            color =
                if (state.telemetryBannerIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
    }
}

@Composable
private fun ViwaMaxIntegrationTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        Text("MAX", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            label = "EXT API токен",
            value = state.maxExtApiToken,
            onValueChange = viewModel::setMaxExtApiToken,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Расширенный ответ", modifier = Modifier.weight(1f))
            Switch(
                checked = state.maxVerificationDetailsEnabled,
                onCheckedChange = viewModel::setMaxVerificationDetailsEnabled,
            )
        }
        Button(
            onClick = { viewModel.saveMaxIntegrationSettings() },
            enabled = !state.integrationsSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить MAX")
        }
        ViwaIntegrationsBanner(state)
    }
}

@Composable
private fun ViwaSbpIntegrationTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        Text("СБП", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            label = "Spot ID (Paymaster)",
            value = state.sbpSpotId,
            onValueChange = viewModel::setSbpSpotId,
        )
        SettingsTextField(
            label = "Секретный ключ",
            value = state.sbpKey,
            onValueChange = viewModel::setSbpKey,
        )
        SettingsTextField(
            label = "Таймаут (сек)",
            value = state.sbpTimeoutSec,
            onValueChange = viewModel::setSbpTimeoutSec,
        )
        Button(
            onClick = { viewModel.saveSbpIntegrationSettings() },
            enabled = !state.integrationsSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить СБП")
        }
        ViwaIntegrationsBanner(state)
    }
}

@Composable
private fun ViwaNanoIntegrationTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        Text("Нанокасса", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        SettingsTextField(label = "kassaId", value = state.nanoKassaId, onValueChange = viewModel::setNanoKassaId)
        SettingsTextField(label = "kassaToken", value = state.nanoKassaToken, onValueChange = viewModel::setNanoKassaToken)
        SettingsTextField(label = "ККТ (zav_kkt)", value = state.nanoKkt, onValueChange = viewModel::setNanoKkt)
        SettingsTextField(label = "Адрес", value = state.nanoAddress, onValueChange = viewModel::setNanoAddress)
        SettingsTextField(label = "Место", value = state.nanoPlace, onValueChange = viewModel::setNanoPlace)
        Button(
            onClick = { viewModel.saveNanoKassaIntegrationSettings() },
            enabled = !state.integrationsSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить Нанокассу")
        }
        ViwaIntegrationsBanner(state)
    }
}

@Composable
private fun ViwaIntegrationsBanner(state: ServiceUiState) {
    state.integrationsBanner?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.integrationsBannerIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
    }
}

@Composable
private fun ViwaEquipmentControllerTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    val selectedUsb by viewModel.equipmentSelectedUsbPath.collectAsStateWithLifecycle()

    SettingsColumn {
        Text("Контроллер (USB UART)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите serial-порт и нажмите «Подключить» — открывается порт и путь сохраняется. Нужны разрешение USB и поддержка CDC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.refreshControllerSerialPorts() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Обновить порты")
            }
            Button(
                onClick = { viewModel.autoFindController() },
                enabled = !state.controllerAutoFindBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Найти контроллер")
            }
        }

        if (state.controllerAutoFindBusy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    state.controllerAutoFindProgress ?: "Сканирование…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.controllerAutoFindBanner?.let { banner ->
            Spacer(Modifier.height(6.dp))
            Text(
                banner,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.controllerAutoFindIsError)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        if (state.controllerSerialPorts.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "USB-устройства не найдены. Нажмите «Обновить порты».",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.controllerSerialPorts.forEach { port ->
                val recognized = port.driverType != "—"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            selectedUsb == port.deviceName -> MaterialTheme.colorScheme.primaryContainer
                            recognized -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        },
                    ),
                    onClick = { viewModel.setEquipmentSelectedUsbPath(port.deviceName) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedUsb == port.deviceName,
                            onClick = { viewModel.setEquipmentSelectedUsbPath(port.deviceName) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                port.deviceName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val isNative = port.driverType == "Native UART"
                            Text(
                                text = when {
                                    isNative -> "Native UART (hardware serial)"
                                    recognized -> "${port.driverType}  ·  VID 0x${port.vendorId.toString(16).uppercase()}  ·  PID 0x${port.productId.toString(16).uppercase()}"
                                    else -> "Неизвестный драйвер  ·  VID 0x${port.vendorId.toString(16).uppercase()}  ·  PID 0x${port.productId.toString(16).uppercase()}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isNative -> MaterialTheme.colorScheme.primary
                                    recognized -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.connectControllerUsb() },
            enabled = !state.controllerEquipmentConnectBusy && selectedUsb.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Подключить")
        }
        if (state.controllerEquipmentConnectBusy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Подключение…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.controllerEquipmentBanner?.let { banner ->
            Spacer(Modifier.height(8.dp))
            Text(banner, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Быстрый тест", style = MaterialTheme.typography.titleSmall)
        Button(
            onClick = { viewModel.runControllerSelfTest() },
            enabled = !state.controllerTestRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Тест контроллера (ReadFirmwareVersion)")
        }
        if (state.controllerTestRunning) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Выполняется тест…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.controllerTestBanner?.let { banner ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = banner,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (state.controllerTestIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        ViwaSerialMonitor(viewModel)
    }
}

@Composable
private fun ViwaSerialMonitor(viewModel: ServiceViewModel) {
    val rawLog by viewModel.controllerRawTrafficFlow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(rawLog.size) {
        if (rawLog.isNotEmpty()) listState.animateScrollToItem(rawLog.size - 1)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Serial monitor (raw hex)", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { viewModel.clearControllerRawLog() }) {
            Text("Очистить", style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(4.dp))

    if (rawLog.isEmpty()) {
        Text(
            "Нет данных. Нажмите «Обновить порты» → выберите порт → «Подключить».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 240.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(6.dp),
        ) {
            LazyColumn(state = listState) {
                items(rawLog, key = { it.id }) { entry ->
                    val color = when (entry.direction) {
                        "TX" -> MaterialTheme.colorScheme.primary
                        "RX" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = entry.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            text = "${entry.direction} [${entry.port}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.width(80.dp),
                        )
                        Text(
                            text = if (entry.hex.isNotEmpty()) entry.hex else entry.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.hex.isNotEmpty()) color else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViwaEquipmentPaymentTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    val terminalStatus by viewModel.terminalVendStatusLine.collectAsStateWithLifecycle()
    SettingsColumn {
        Text("aQsi USB", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        InfoRow("Статус терминала", terminalStatus)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.runPaymentTerminal048Demo() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Тест 0x48 — уведомление контроллера (СБП)")
        }
        state.paymentTerminalTestBanner?.let { banner ->
            Spacer(Modifier.height(8.dp))
            Text(banner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ViwaSettingsDevModeTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    SettingsColumn {
        Text("Режим разработки", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Мок контроллера", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.useMockController,
                onCheckedChange = viewModel::setUseMockController,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Как dev-флаг в ТЗ: при включении используется MockControllerTransport вместо serial.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Бесплатный режим (как freeMode в wiva)",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.devFreeMode,
                onCheckedChange = viewModel::setDevFreeMode,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Выкл.: на главном экране кнопка «Оплатить» шлёт 0x48; в режиме мока затем симулируется PAX «Оплата прошла» (код 4), затем ChooseDrink.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ViwaWindowTab() {
    val activity = LocalContext.current as? Activity
    var confirmed by remember { mutableStateOf(false) }

    SettingsColumn {
        Text("Управление окном", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Завершает процесс приложения. Используйте только если необходимо покинуть kiosk-режим для обслуживания устройства.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (!confirmed) {
            Button(
                onClick = { confirmed = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Закрыть приложение", color = MaterialTheme.colorScheme.onError)
            }
        } else {
            Text(
                "Вы уверены? Это закроет приложение.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { confirmed = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Отмена")
                }
                Button(
                    onClick = { activity?.finishAffinity() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Подтвердить", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}

