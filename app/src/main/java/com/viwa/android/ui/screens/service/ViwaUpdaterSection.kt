package com.viwa.android.ui.screens.service

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Блок OTA: telemetry manifest, проверка, загрузка и установка APK. */
@Composable
fun ViwaUpdaterSection(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
    embedded: Boolean = false,
) {
    val progress by viewModel.updateInstallProgress.collectAsStateWithLifecycle()
    var legacyHost by remember(state.updateHost) { mutableStateOf(state.updateHost) }
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
            Text(
                "Обновления",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow("Текущая версия", "${state.currentVersion} (${state.currentVersionCode})")
            state.otaChannel?.let { channel ->
                InfoRow("Канал", channel)
            }
            state.otaPhase?.let { phase ->
                InfoRow("Статус OTA", phaseLabel(phase))
            }
            when (state.otaServerFeatureEnabled) {
                true -> Text("Автопроверка: включена (раз в 6 ч)", style = MaterialTheme.typography.bodySmall)
                false -> Text("Автопроверка: выключена сервером", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                null -> Text("Автопроверка: ожидание hello WS", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Legacy HTTP (debug/fallback)", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.otaLegacyFallbackEnabled,
                    onCheckedChange = { viewModel.setLegacyOtaFallbackEnabled(it) },
                )
            }
            if (state.otaLegacyFallbackEnabled) {
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    label = "Legacy URL сервера",
                    value = legacyHost,
                    onValueChange = { legacyHost = it },
                    placeholder = "https://tl.vitamin-water.ru/android-ota",
                )
                Button(onClick = { viewModel.setUpdateHost(legacyHost) }) {
                    Text("Сохранить legacy URL")
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.checkForUpdates() },
                enabled = !state.isCheckingUpdate && !state.isInstalling,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить обновления")
            }
            if (state.isCheckingUpdate) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Проверка обновлений...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.updateCheckError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.isUpToDate && !state.isCheckingUpdate) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Установлена актуальная версия",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.availableUpdate?.let { update ->
                Spacer(Modifier.height(16.dp))
                val versionLabel =
                    buildString {
                        append(update.version)
                        update.versionCode?.let { append(" (code $it)") }
                    }
                Text(
                    "Доступна версия: $versionLabel",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (update.changelog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        update.changelog,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.installUpdate(update) },
                    enabled = !state.isInstalling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Установить")
                }
                if (update.telemetryOffer) {
                    Text(
                        "Установка требует scope firmware.update (online)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isInstalling) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Загрузка и проверка...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            progress?.let { p ->
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { p.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val downloaded = formatBytes(p.bytesDownloaded)
                val totalPart =
                    if (p.totalBytes > 0) {
                        " / ${formatBytes(p.totalBytes)} (${(p.progress * 100).toInt()}%)"
                    } else {
                        ""
                    }
                Text(
                    "$downloaded$totalPart",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
    if (embedded) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            contentColor = scheme.onBackground,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}

private fun phaseLabel(phase: String): String =
    when (phase) {
        "Idle" -> "Ожидание"
        "Checking" -> "Проверка"
        "Offered" -> "Доступно обновление"
        "Downloading" -> "Загрузка"
        "Verifying" -> "Проверка APK"
        "Installing" -> "Установка"
        "AwaitingUser" -> "Подтвердите установку"
        "Success" -> "Успешно"
        "Failed" -> "Ошибка"
        else -> phase
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f КБ".format(bytes / 1_024.0)
        else -> "$bytes Б"
    }
