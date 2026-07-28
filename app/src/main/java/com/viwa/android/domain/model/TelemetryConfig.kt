package com.viwa.android.domain.model

import kotlinx.serialization.Serializable

/** Эндпоинты Simple Telemetry MVP (REST enroll + JWT WS). */
@Serializable
data class TelemetryConfig(
    val apiUrl: String = DEFAULT_API_URL,
    val wsUrl: String = "",
) {
    companion object {
        const val DEFAULT_API_URL = "https://tl.vitamin-water.ru"

        /** Сбрасывает устаревший Shaker WS из сохранённого конфига. */
        fun normalize(config: TelemetryConfig): TelemetryConfig {
            val trimmed = config.wsUrl.trim()
            return config.copy(wsUrl = if (isLegacyWsUrl(trimmed)) "" else trimmed)
        }

        fun sanitizeWsUrl(wsUrl: String): String {
            val trimmed = wsUrl.trim()
            return if (isLegacyWsUrl(trimmed)) "" else trimmed
        }

        private fun isLegacyWsUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            val normalized = url.removeSuffix("/")
            return normalized.contains("185.46.8.39:8315", ignoreCase = true) ||
                normalized.equals("ws://185.46.8.39:8315/ws", ignoreCase = true)
        }
    }
}
