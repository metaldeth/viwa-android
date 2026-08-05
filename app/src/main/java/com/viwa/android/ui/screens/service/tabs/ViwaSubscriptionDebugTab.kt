package com.viwa.android.ui.screens.service.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.viwa.android.ui.screens.service.ServiceUiState
import com.viwa.android.ui.screens.service.ServiceViewModel
import com.viwa.android.ui.screens.service.SettingsColumn
import com.viwa.android.ui.screens.service.SettingsTextField

private const val DEFAULT_TEST_UUID = "2caaf0b2-2b7f-4c09-9bef-dafd984c9a66"

@Composable
fun ViwaSubscriptionDebugTab(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
) {
    var testUuid by rememberSaveable { mutableStateOf(DEFAULT_TEST_UUID) }

    SettingsColumn {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Отладка подписки", style = MaterialTheme.typography.headlineSmall)

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Режим отладки подписки", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "При включении появляется кнопка «Карта +7922» в левом верхнем углу (эмуляция скана карты клиента +79220389216) и FAB на экране напитков для WS и controller-логов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.subscriptionDebugEnabled,
                    onCheckedChange = viewModel::setSubscriptionDebugEnabled,
                )
            }

            HorizontalDivider()

            Text("Тест statusSubscribeTopic", style = MaterialTheme.typography.titleMedium)
            Text(
                "Отправляет запрос вручную с указанным UUID клиента — без физического сканирования QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsTextField(
                label = "clientId (UUID)",
                value = testUuid,
                onValueChange = { testUuid = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fieldKey = "subscription_debug_uuid",
                maxLength = 128,
            )

            Button(
                onClick = { viewModel.sendSubscriptionDebugRequest(testUuid) },
                enabled = !state.subscriptionDebugSendBusy && testUuid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.subscriptionDebugSendBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text("Отправить statusSubscribeTopic")
            }

            state.subscriptionDebugSendResult?.let { result ->
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color =
                        if (result.startsWith("✓")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "WS и controller-логи — вкладки «Логи сети» и «Дебаг контроллера» этого раздела, либо FAB на экране напитков (при включённом режиме отладки).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
