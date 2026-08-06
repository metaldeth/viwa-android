package com.viwa.android.hardware.serial

/**
 * When Android re-enumerates USB, [SerialDeviceInfo.deviceName] changes (e.g. `003/014` → `003/015`)
 * while the physical device stays the same.
 *
 * [findReplacementDevice] picks a single unambiguous replacement: exactly one connected serial
 * candidate whose stored role is unassigned or matches the requested [PortRole]. Returns `null`
 * when the stale path is still present, when there are zero candidates, or when multiple candidates
 * would make reassignment ambiguous. [ViwaSerialPortImpl.assignedDeviceName] persists the new path
 * when a replacement is found.
 */
internal object UsbAssignmentMigration {
    fun findReplacementDevice(
        role: PortRole,
        staleDeviceName: String,
        devices: List<SerialDeviceInfo>,
        assignments: Map<String, PortRole>,
    ): SerialDeviceInfo? {
        if (devices.any { it.deviceName == staleDeviceName }) return null
        val storedRole = role.toAssignmentStorage()
        val candidates =
            devices
                .filter { it.driverType != null }
                .filter { device ->
                    when (assignments[device.deviceName]?.toAssignmentStorage()) {
                        null, storedRole -> true
                        else -> false
                    }
                }
        return when (candidates.size) {
            1 -> candidates.single()
            else -> null
        }
    }
}
