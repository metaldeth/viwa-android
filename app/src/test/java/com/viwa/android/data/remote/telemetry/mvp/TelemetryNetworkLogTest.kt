package com.viwa.android.data.remote.telemetry.mvp

import android.net.wifi.WifiManager
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryNetworkLogTest {
    @Test
    fun `should keep snapshot identity stable when only rssi changes`() {
        val weak =
            TelemetryNetworkSnapshot(
                validated = true,
                hasInternet = true,
                captivePortal = false,
                transport = "WIFI",
                wifiEnabled = true,
                wifiState = "ENABLED",
                rssi = -40,
                ssid = "shop",
            )
        val weaker = weak.copy(rssi = -70)
        assertEquals(weak.identityLine(), weaker.identityLine())
        assertFalse(weak.toLogLine() == weaker.toLogLine())
        assertTrue(weak.toLogLine().contains("rssi=-40"))
    }

    @Test
    fun `should compact nested network exceptions without stack frames`() {
        val error = UnknownHostException("Unable to resolve host \"tl.vitamin-water.ru\"")
        val compact = compactThrowableMessage(error)
        assertTrue(compact.contains("UnknownHostException"))
        assertTrue(compact.contains("tl.vitamin-water.ru"))
        assertFalse(compact.contains("at "))
    }

    @Test
    fun `should sanitize unknown and quoted ssid`() {
        assertEquals("unknown", sanitizeWifiSsid(null))
        assertEquals("unknown", sanitizeWifiSsid("<unknown ssid>"))
        assertEquals("CafeWiFi", sanitizeWifiSsid("\"CafeWiFi\""))
        assertEquals("a".repeat(32), sanitizeWifiSsid("a".repeat(40)))
    }

    @Test
    fun `should map wifi radio states`() {
        assertEquals("DISABLED", wifiStateLabel(WifiManager.WIFI_STATE_DISABLED))
        assertEquals("ENABLED", wifiStateLabel(WifiManager.WIFI_STATE_ENABLED))
        assertEquals("DISABLING", wifiStateLabel(WifiManager.WIFI_STATE_DISABLING))
    }
}
