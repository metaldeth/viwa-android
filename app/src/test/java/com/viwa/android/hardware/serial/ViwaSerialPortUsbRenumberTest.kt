package com.viwa.android.hardware.serial

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.services.payment.PillUsbSessionOwnerImpl
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ViwaSerialPortUsbRenumberTest {
    @Test
    fun assignedDeviceNameShouldMigrateScannerToNewUsbPath() =
        runTest {
            val stale = "/dev/bus/usb/003/014"
            val live = "/dev/bus/usb/003/015"
            val discovery =
                mockk<ViwaSerialDiscovery> {
                    every { availableDevices() } returns
                        listOf(
                            SerialDeviceInfo(live, 1240, 10, "CdcAcmSerialDriver"),
                        )
                }
            val usbSerialManager =
                mockk<UsbSerialManager> {
                    every { enumerateSerialDevices() } returns discovery.availableDevices()
                    every { getAvailableDevices() } returns emptyList()
                    every { getConnectedUsbDevices() } returns emptyList()
                }
            val assignments =
                mutableMapOf(stale to PortRole.SCANNER.name)
            val configRepository =
                object : ConfigRepository {
                    override suspend fun get(key: String): String? = null

                    override suspend fun set(key: String, value: String) = Unit

                    override suspend fun delete(key: String) = Unit

                    override suspend fun getJson(key: String): String? =
                        if (key == JsonStoreKeys.PORT_ASSIGNMENTS) {
                            Json.encodeToString(assignments)
                        } else {
                            null
                        }

                    override suspend fun setJson(key: String, jsonStr: String) {
                        if (key == JsonStoreKeys.PORT_ASSIGNMENTS) {
                            assignments.clear()
                            assignments.putAll(
                                Json.decodeFromString<Map<String, String>>(jsonStr),
                            )
                        }
                    }
                }
            val serialPortManager =
                mockk<SerialPortManager> {
                    coEvery { getPortAssignments() } coAnswers {
                        assignments.mapValues { (_, roleName) ->
                            PortRole.valueOf(roleName)
                        }
                    }
                    coEvery { replacePortAssignments(any()) } coAnswers {
                        val next = firstArg<Map<String, PortRole>>()
                        assignments.clear()
                        assignments.putAll(next.mapValues { it.value.name })
                    }
                }
            val serialPort =
                ViwaSerialPortImpl(
                    usbSerialManager = usbSerialManager,
                    serialPortManager = serialPortManager,
                    configRepository = configRepository,
                    assignmentEvents = SerialPortAssignmentEvents(),
                    pillUsbSessionOwner = PillUsbSessionOwnerImpl(),
                )

            assertEquals(live, serialPort.assignedDeviceName(PortRole.SCANNER))
            assertEquals(live, assignments.keys.single())
            assertEquals(PortRole.SCANNER.name, assignments[live])
        }
}
