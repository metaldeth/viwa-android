package com.viwa.android.hardware.serial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbAssignmentMigrationTest {
    @Test
    fun shouldPickSingleSerialDeviceWhenAssignedPathIsStale() {
        val stale = "/dev/bus/usb/003/014"
        val live = "/dev/bus/usb/003/015"
        val devices = listOf(SerialDeviceInfo(live, 1240, 10, "CdcAcmSerialDriver"))
        val assignments = mapOf(stale to PortRole.SCANNER)

        val replacement =
            UsbAssignmentMigration.findReplacementDevice(
                PortRole.SCANNER,
                stale,
                devices,
                assignments,
            )

        assertEquals(live, replacement?.deviceName)
    }

    @Test
    fun shouldReturnNullWhenMultipleSerialCandidatesExist() {
        val stale = "/dev/bus/usb/003/014"
        val devices =
            listOf(
                SerialDeviceInfo("/dev/a", 1, 1, "CdcAcmSerialDriver"),
                SerialDeviceInfo("/dev/b", 2, 2, "CdcAcmSerialDriver"),
            )

        assertNull(
            UsbAssignmentMigration.findReplacementDevice(
                PortRole.SCANNER,
                stale,
                devices,
                mapOf(stale to PortRole.SCANNER),
            ),
        )
    }
}
