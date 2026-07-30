package com.viwa.android.ui.screens.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMenuTestTagsTest {

    @Test
    fun allDeclaredTags_areUnique() {
        val tags = ServiceMenuTestTags.allDeclaredTags.toList()
        assertEquals(tags.size, tags.distinct().size)
    }

    @Test
    fun allDeclaredTags_matchNamingConvention() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        for (tag in ServiceMenuTestTags.allDeclaredTags) {
            assertTrue("Invalid tag: $tag", pattern.matches(tag))
        }
    }

    @Test
    fun telemetrySmokeTags_areDocumentedConstants() {
        assertEquals("telemetry_connection_root", ServiceMenuTestTags.TELEMETRY_CONNECTION_ROOT)
        assertEquals("telemetry_serial_input", ServiceMenuTestTags.TELEMETRY_SERIAL_INPUT)
        assertEquals("telemetry_reserve_serial", ServiceMenuTestTags.TELEMETRY_RESERVE_SERIAL)
        assertEquals("telemetry_register", ServiceMenuTestTags.TELEMETRY_REGISTER)
        assertEquals("telemetry_auto_provision", ServiceMenuTestTags.TELEMETRY_AUTO_PROVISION)
        assertEquals("telemetry_connect_ws", ServiceMenuTestTags.TELEMETRY_CONNECT_WS)
        assertEquals("telemetry_api_url_input", ServiceMenuTestTags.TELEMETRY_API_URL_INPUT)
        assertEquals("telemetry_ws_url_input", ServiceMenuTestTags.TELEMETRY_WS_URL_INPUT)
    }

    @Test
    fun serviceNavigationTags_resolveForTelemetryConnectionFlow() {
        assertEquals("service_group_telemetry", ServiceMenuTestTags.serviceGroupTag(ViwaServiceGroupId.Telemetry))
        assertEquals(
            "service_subtab_telemetry_connection",
            ServiceMenuTestTags.serviceSubTabTag(ViwaServiceSubTabId.TelemetryConnection),
        )
        assertEquals(
            "service_subtab_telemetry_addresses",
            ServiceMenuTestTags.serviceSubTabTag(ViwaServiceSubTabId.TelemetryAddresses),
        )
    }

    @Test
    fun syntheticResourceIdPrefix_isViwaPackage() {
        val syntheticId = "com.viwa.android:id/${ServiceMenuTestTags.TELEMETRY_REGISTER}"
        assertTrue(syntheticId.startsWith("com.viwa.android:id/"))
        assertEquals("com.viwa.android:id/telemetry_register", syntheticId)
    }
}
