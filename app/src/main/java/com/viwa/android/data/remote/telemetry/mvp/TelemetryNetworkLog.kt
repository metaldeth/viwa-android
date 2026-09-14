package com.viwa.android.data.remote.telemetry.mvp

import android.net.wifi.WifiManager

internal data class TelemetryNetworkSnapshot(
    val validated: Boolean,
    val hasInternet: Boolean,
    val captivePortal: Boolean,
    val transport: String,
    val wifiEnabled: Boolean?,
    val wifiState: String,
    val rssi: Int?,
    val ssid: String,
) {
    fun toLogLine(): String =
        "validated=$validated hasInternet=$hasInternet captivePortal=$captivePortal " +
            "transport=$transport wifiEnabled=$wifiEnabled wifiState=$wifiState rssi=$rssi ssid=$ssid"

    /** Identity without RSSI so signal flicker does not spam log ship. */
    fun identityLine(): String =
        "validated=$validated hasInternet=$hasInternet captivePortal=$captivePortal " +
            "transport=$transport wifiEnabled=$wifiEnabled wifiState=$wifiState ssid=$ssid"
}

internal fun compactThrowableMessage(error: Throwable): String =
    generateSequence(error) { it.cause }
        .distinct()
        .take(3)
        .joinToString(" <- ") { throwable ->
            val message = throwable.message?.replace('\n', ' ')?.trim().orEmpty().take(180)
            if (message.isEmpty()) {
                throwable.javaClass.simpleName
            } else {
                "${throwable.javaClass.simpleName}: $message"
            }
        }

internal fun wifiStateLabel(state: Int): String =
    when (state) {
        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
        WifiManager.WIFI_STATE_UNKNOWN -> "UNKNOWN"
        else -> "OTHER($state)"
    }

internal fun sanitizeWifiSsid(raw: String?): String {
    val trimmed = raw?.trim()?.trim('"').orEmpty()
    return if (trimmed.isEmpty() || trimmed.equals("<unknown ssid>", ignoreCase = true)) {
        "unknown"
    } else {
        trimmed.take(32)
    }
}
