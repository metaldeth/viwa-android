package com.viwa.android.ui.screens.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ViwaServiceMenuStructureTest {
    @Test
    fun serviceMenuGroups_keepCanonicalOrder() {
        val groupIds = ViwaServiceMenuGroups.map { it.id }
        assertEquals(
            listOf(
                ViwaServiceGroupId.Maintenance,
                ViwaServiceGroupId.Dashboard,
                ViwaServiceGroupId.Telemetry,
                ViwaServiceGroupId.Debug,
                ViwaServiceGroupId.Integrations,
                ViwaServiceGroupId.Equipment,
                ViwaServiceGroupId.Settings,
            ),
            groupIds,
        )
    }

    @Test
    fun telemetrySubTabs_keepConnectionAndAddressesOnly() {
        val telemetry = findViwaServiceGroup(ViwaServiceGroupId.Telemetry)
        assertEquals(
            listOf(
                ViwaServiceSubTabId.TelemetryConnection,
                ViwaServiceSubTabId.TelemetryAddresses,
            ),
            telemetry.subTabs.map { it.id },
        )
    }

    @Test
    fun debugSubTabs_keepWsLogsAndControllerOnly() {
        val debug = findViwaServiceGroup(ViwaServiceGroupId.Debug)
        assertEquals(
            listOf(
                ViwaServiceSubTabId.DebugWsLogs,
                ViwaServiceSubTabId.DebugController,
            ),
            debug.subTabs.map { it.id },
        )
    }

    @Test
    fun equipmentSubTabs_showDevicesOnly() {
        val equipment = findViwaServiceGroup(ViwaServiceGroupId.Equipment)
        assertEquals("Устройства", equipment.label)
        assertEquals(
            listOf(ViwaServiceSubTabId.Devices),
            equipment.subTabs.map { it.id },
        )
    }
}
