package com.viwa.android.hardware.serial

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.services.payment.PillUsbSessionOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class ViwaSerialPortImpl
@Inject
constructor(
    private val usbSerialManager: UsbSerialManager,
    private val serialPortManager: SerialPortManager,
    private val configRepository: ConfigRepository,
    private val assignmentEvents: SerialPortAssignmentEvents,
    private val pillUsbSessionOwner: PillUsbSessionOwner,
) : ViwaSerialPort {

    override suspend fun availableDevices(): List<SerialDeviceInfo> =
        withContext(Dispatchers.IO) {
            usbSerialManager.enumerateSerialDevices().sortedWith(
                compareBy({ it.vendorId }, { it.productId }, { it.deviceName }),
            )
        }

    override suspend fun assignments(): Map<String, PortRole> =
        serialPortManager.getPortAssignments().mapValues { (_, role) ->
            role.fromAssignmentStorage()
        }

    override suspend fun assign(deviceName: String, role: PortRole): Result<Unit> =
        runCatching {
            val stored = role.toAssignmentStorage()
            val current = serialPortManager.getPortAssignments().toMutableMap()
            if (stored == PortRole.UNASSIGNED) {
                current.remove(deviceName)
                if (configRepository.get(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH) == deviceName) {
                    configRepository.set(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH, "")
                }
            } else {
                current.entries.removeAll { it.value == stored && it.key != deviceName }
                current[deviceName] = stored
                if (role == PortRole.CONTROLLER) {
                    configRepository.set(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH, deviceName)
                }
            }
            serialPortManager.replacePortAssignments(current)
            assignmentEvents.notifyChanged()
        }

    override suspend fun assignedDeviceName(role: PortRole): String? {
        val devices = availableDevices()
        val assignments = serialPortManager.getPortAssignments()
        val assignedKey =
            assignments.entries.firstOrNull { (path, assignedRole) ->
                assignedRole == role ||
                    assignedRole.toAssignmentStorage() == role.toAssignmentStorage() ||
                    (role == PortRole.CONTROLLER && assignedRole == PortRole.CONTROLLER)
            }?.key
        if (assignedKey == null) {
            if (role == PortRole.CONTROLLER) {
                return controllerDevicePath()
            }
            return null
        }
        if (devices.any { it.deviceName == assignedKey }) return assignedKey
        val replacement =
            UsbAssignmentMigration.findReplacementDevice(role, assignedKey, devices, assignments)
                ?: return null
        assign(replacement.deviceName, role).getOrThrow()
        Timber.i("Serial: migrated %s from %s to %s", role, assignedKey, replacement.deviceName)
        return replacement.deviceName
    }

    override suspend fun probeOpen(deviceName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (deviceName.startsWith("/dev/tty")) {
                return@withContext Result.success(Unit)
            }
            val deviceMeta =
                usbSerialManager.enumerateSerialDevices()
                    .firstOrNull { it.deviceName == deviceName }
            if (deviceMeta != null && AqsiPillUsbIdentifiers.isAqsiPill(deviceMeta)) {
                if (pillUsbSessionOwner.activeOwner.value != null) {
                    return@withContext Result.failure(
                        IllegalStateException("Pill USB session busy"),
                    )
                }
            }
            val driver =
                usbSerialManager.getAvailableDevices()
                    .firstOrNull { it.device.deviceName == deviceName }
            if (driver == null) {
                val connected =
                    usbSerialManager.getConnectedUsbDevices()
                        .any { it.deviceName == deviceName }
                return@withContext if (connected) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Устройство не найдено"))
                }
            }
            if (!usbSerialManager.hasPermission(driver.device)) {
                return@withContext Result.failure(IllegalStateException("Нет разрешения USB"))
            }
            val opened =
                usbSerialManager.openConnection(driver)
                    ?: return@withContext Result.failure(IllegalStateException("Не удалось открыть порт"))
            opened.first.close()
            opened.second.close()
            Result.success(Unit)
        }

    override suspend fun controllerDevicePath(): String? {
        val assigned = assignedDeviceName(PortRole.CONTROLLER)
        if (!assigned.isNullOrBlank()) return assigned
        return configRepository.get(JsonStoreKeys.CONTROLLER_USB_DEVICE_PATH)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
