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
        const val DEFAULT_WS_URL = "wss://tl.vitamin-water.ru/api/v1/machines/ws"
        const val DEFAULT_TOKEN_ENDPOINT = "/api/v1/machines/token"
        const val DEFAULT_TOKEN_URL = "https://tl.vitamin-water.ru/api/v1/machines/token"

        /** Устаревшие стенды — подменяем на прод-дефолт при загрузке конфига. */
        private val DEPRECATED_HOSTS = setOf("tl.asnefedov.ru")

        /**
         * Нормализует сохранённый конфиг: legacy Shaker WS → пусто (derive),
         * устаревший API/WS host → [DEFAULT_API_URL] / пустой WS.
         */
        fun normalize(config: TelemetryConfig): TelemetryConfig {
            val apiUrl = migrateDeprecatedApiUrl(config.apiUrl)
            val wsUrl = sanitizeWsUrl(config.wsUrl)
            return config.copy(apiUrl = apiUrl, wsUrl = wsUrl)
        }

        fun sanitizeWsUrl(wsUrl: String): String {
            val trimmed = wsUrl.trim()
            if (trimmed.isEmpty()) return ""
            if (isLegacyWsUrl(trimmed) || isDeprecatedHostUrl(trimmed)) return ""
            return trimmed
        }

        fun migrateDeprecatedApiUrl(apiUrl: String): String {
            val trimmed = apiUrl.trim()
            if (trimmed.isEmpty() || isDeprecatedHostUrl(trimmed)) return DEFAULT_API_URL
            return trimmed
        }

        /** Absolute token URL на deprecated host → прод; relative оставляем. */
        fun migrateTokenEndpoint(tokenEndpoint: String): String {
            val trimmed = tokenEndpoint.trim().ifBlank { DEFAULT_TOKEN_ENDPOINT }
            if (!trimmed.startsWith("http", ignoreCase = true)) return trimmed
            return if (isDeprecatedHostUrl(trimmed)) DEFAULT_TOKEN_URL else trimmed
        }

        /** Absolute WS на deprecated/legacy → пусто (derive от API). */
        fun migrateWsProtocolUrl(wsProtocolUrl: String): String = sanitizeWsUrl(wsProtocolUrl)

        fun isDeprecatedHostUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            val host =
                url
                    .trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("wss://")
                    .removePrefix("ws://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringBefore(':')
                    .lowercase()
            return host in DEPRECATED_HOSTS
        }

        private fun isLegacyWsUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            val normalized = url.removeSuffix("/")
            return normalized.contains("185.46.8.39:8315", ignoreCase = true) ||
                normalized.equals("ws://185.46.8.39:8315/ws", ignoreCase = true)
        }
    }
}
