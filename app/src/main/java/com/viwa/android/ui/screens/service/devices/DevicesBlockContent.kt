package com.viwa.android.ui.screens.service.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.viwa.android.hardware.serial.SerialDeviceInfo
import com.viwa.android.hardware.serial.PortRole

@Composable
fun DevicesBlockContent(
    state: DevicesBlockState,
    actions: DevicesBlockActions,
    modifier: Modifier = Modifier,
    embeddedInScrollParent: Boolean = false,
    blockLabels: DevicesBlockUiLabels? = null,
) {
    val labels = blockLabels ?: DevicesBlockUiLabels.KioskDefaults
    var showScannerDiscovery by remember { mutableStateOf(false) }
    var showPaymentDiscovery by remember { mutableStateOf(false) }

    if (showScannerDiscovery) {
        FindScannerDialog(
            drivers = state.scannerDiscoveryResults,
            isRunning = state.isScannerDiscoveryRunning,
            onStart = actions::startScannerDiscovery,
            onStop = actions::stopScannerDiscovery,
            onSelect = { deviceName ->
                actions.assignRole(deviceName, PortRole.SCANNER)
                actions.stopScannerDiscovery()
                showScannerDiscovery = false
            },
            onDismiss = {
                actions.stopScannerDiscovery()
                showScannerDiscovery = false
            },
            labels = labels,
        )
    }

    if (showPaymentDiscovery) {
        FindPaymentDialog(
            drivers = state.paymentDiscoveryResults,
            isRunning = state.isPaymentDiscoveryRunning,
            onStart = actions::startPaymentDiscovery,
            onStop = actions::stopPaymentDiscovery,
            onSelect = { deviceName ->
                actions.assignRole(deviceName, PortRole.PAYMENT)
                actions.stopPaymentDiscovery()
                showPaymentDiscovery = false
            },
            onDismiss = {
                actions.stopPaymentDiscovery()
                showPaymentDiscovery = false
            },
            labels = labels,
        )
    }

    Column(
        modifier =
            if (embeddedInScrollParent) {
                modifier.fillMaxWidth().padding(16.dp)
            } else {
                modifier.fillMaxSize().padding(16.dp)
            },
    ) {
        Text(
            text = labels.title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = labels.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        ScannerStatusCard(status = state.scannerStatus, labels = labels)
        Spacer(Modifier.height(8.dp))
        PaymentStatusCard(status = state.paymentStatus, labels = labels)
        if (state.controllerStatus !is ControllerDeviceStatus.NotSupported) {
            Spacer(Modifier.height(8.dp))
            ControllerStatusCard(
                status = state.controllerStatus,
                primarySectionTitle = null,
                labels = labels,
            )
            if (state.showControllerMockToggle) {
                Spacer(Modifier.height(8.dp))
                ControllerMockModeRow(
                    enabled = state.isControllerMockEnabled,
                    onCheckedChange = actions::setControllerMockEnabled,
                    labels = labels,
                )
            }
            state.controllerDiscoveryMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.scannerDiscoveryMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.paymentDiscoveryMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))

        if (state.devices.isEmpty()) {
            Text(
                text = labels.noDevicesFound,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (embeddedInScrollParent) {
                state.devices.forEach { device ->
                    val role = state.assignments[device.deviceName] ?: PortRole.UNKNOWN
                    UsbDeviceItem(
                        device = device,
                        role = role,
                        onRoleChange = { newRole -> actions.assignRole(device.deviceName, newRole) },
                        hidePrimaryControllerChip = false,
                        labels = labels,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.devices, key = { it.deviceName }) { device ->
                        val role = state.assignments[device.deviceName] ?: PortRole.UNKNOWN
                        UsbDeviceItem(
                            device = device,
                            role = role,
                            onRoleChange = { newRole -> actions.assignRole(device.deviceName, newRole) },
                            hidePrimaryControllerChip = false,
                            labels = labels,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (state.usesStartupDiscovery) {
            DiscoveryActionButton(
                label = labels.findScanner,
                inProgressLabel = labels.discoveryInProgress,
                isRunning = state.isScannerDiscoveryRunning,
                onClick = actions::startScannerDiscovery,
            )
        } else {
            Button(
                onClick = { showScannerDiscovery = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.findScanner)
            }
        }
        if (state.controllerStatus !is ControllerDeviceStatus.NotSupported) {
            Spacer(Modifier.height(8.dp))
            DiscoveryActionButton(
                label = labels.findController,
                inProgressLabel = labels.discoveryInProgress,
                isRunning = state.isControllerDiscoveryRunning,
                enabled = !state.isControllerMockEnabled,
                onClick = actions::startControllerAutoDiscovery,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.usesStartupDiscovery) {
            DiscoveryActionButton(
                label = labels.findPayment,
                inProgressLabel = labels.discoveryInProgress,
                isRunning = state.isPaymentDiscoveryRunning,
                onClick = actions::startPaymentDiscovery,
            )
        } else {
            Button(
                onClick = { showPaymentDiscovery = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.findPayment)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = actions::refreshDevices,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(labels.refreshList)
        }
    }
}

@Composable
private fun ScannerStatusCard(
    status: ScannerDeviceStatus,
    labels: DevicesBlockUiLabels,
) {
    val (title, details, isError) =
        when (status) {
            ScannerDeviceStatus.NotAssigned ->
                Triple(
                    labels.scannerNotAssignedTitle,
                    labels.scannerNotAssignedDetails,
                    false,
                )
            is ScannerDeviceStatus.Connected ->
                Triple(
                    labels.scannerConnectedTitle,
                    labels.scannerConnectedPort.format(status.deviceName),
                    false,
                )
            is ScannerDeviceStatus.Error ->
                Triple(
                    labels.scannerErrorTitle,
                    labels.scannerErrorDetails.format(status.deviceName, status.message),
                    true,
                )
        }
    StatusCard(title = title, details = details, isError = isError)
}

@Composable
private fun PaymentStatusCard(
    status: PaymentDeviceStatus,
    labels: DevicesBlockUiLabels,
) {
    val (title, details, isError) =
        when (status) {
            PaymentDeviceStatus.NotAssigned ->
                Triple(
                    labels.paymentNotAssignedTitle,
                    labels.paymentNotAssignedDetails,
                    false,
                )
            is PaymentDeviceStatus.Ready ->
                Triple(
                    labels.paymentReadyTitle,
                    labels.paymentReadyPort.format(status.deviceName),
                    false,
                )
            is PaymentDeviceStatus.Busy ->
                Triple(
                    labels.paymentBusyTitle,
                    labels.paymentBusyDetails.format(status.deviceName, status.message),
                    false,
                )
            is PaymentDeviceStatus.Error ->
                Triple(
                    labels.paymentErrorTitle,
                    labels.paymentErrorDetails.format(status.deviceName, status.message),
                    true,
                )
        }
    StatusCard(title = title, details = details, isError = isError)
}

@Composable
private fun ControllerStatusCard(
    status: ControllerDeviceStatus,
    primarySectionTitle: String? = null,
    labels: DevicesBlockUiLabels,
) {
    val (title, details, isError) =
        when (status) {
            ControllerDeviceStatus.NotSupported ->
                Triple("", "", false)
            ControllerDeviceStatus.MockActive ->
                Triple(
                    primarySectionTitle?.let { labels.controllerMockTitleWithSection.format(it) }
                        ?: labels.controllerMockTitle,
                    labels.controllerMockDetails,
                    false,
                )
            ControllerDeviceStatus.NotAssigned ->
                Triple(
                    primarySectionTitle ?: labels.controllerNotAssignedTitle,
                    if (primarySectionTitle != null) {
                        labels.controllerNotAssignedDetailsPrimary
                    } else {
                        labels.controllerNotAssignedDetailsLegacy
                    },
                    false,
                )
            is ControllerDeviceStatus.Assigned ->
                Triple(
                    primarySectionTitle ?: labels.controllerAssignedTitle,
                    labels.controllerAssignedPort.format(status.deviceName),
                    false,
                )
            is ControllerDeviceStatus.Error ->
                Triple(
                    labels.controllerErrorTitle,
                    labels.controllerErrorDetails.format(status.deviceName, status.message),
                    true,
                )
        }
    if (status is ControllerDeviceStatus.NotSupported) return
    StatusCard(title = title, details = details, isError = isError)
}

@Composable
private fun StatusCard(
    title: String,
    details: String,
    isError: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color =
                    if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = details,
                color =
                    if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ControllerMockModeRow(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    labels: DevicesBlockUiLabels,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = labels.mockControllerTitle,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = labels.mockControllerDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun UsbDeviceItem(
    device: SerialDeviceInfo,
    role: PortRole,
    onRoleChange: (PortRole) -> Unit,
    hidePrimaryControllerChip: Boolean = false,
    labels: DevicesBlockUiLabels,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = device.deviceName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (device.vendorId != 0 || device.productId != 0) {
                Text(
                    text = "VID: ${device.vendorId.toHex4()} PID: ${device.productId.toHex4()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text =
                    device.driverType?.let { labels.serialDriverFound.format(it) }
                        ?: labels.serialDriverNotFound,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.driverType != null || device.deviceName.startsWith("/dev/tty")) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PortRole.entries
                        .filter { portRole ->
                            portRole != PortRole.UNKNOWN && portRole != PortRole.UNASSIGNED
                        }
                        .filter { portRole ->
                            if (!hidePrimaryControllerChip) return@filter true
                            portRole != PortRole.CONTROLLER
                        }
                        .forEach { portRole ->
                            FilterChip(
                                selected = role == portRole,
                                onClick = { onRoleChange(portRole) },
                                label = {
                                    Text(
                                        text = portRole.name,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                            )
                        }
                }
            }
        }
    }
}

private fun Int.toHex4(): String = "%04X".format(this)

@Composable
private fun DiscoveryActionButton(
    label: String,
    inProgressLabel: String,
    isRunning: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isRunning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isRunning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(inProgressLabel)
            }
        } else {
            Text(label)
        }
    }
}

