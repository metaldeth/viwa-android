package com.viwa.android.domain.telemetry

import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.model.customer.toDrinkWaterOption
import com.viwa.android.domain.model.customer.toFlowWaterPourType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Cross-scan plain-water preference: telemetry `lastPlainWaterType` + per-device cache. */
object LoyaltyPlainWaterPreference {
    private val json = Json { ignoreUnknownKeys = true }

    fun wireToDrinkWaterOption(wire: String?): DrinkWaterOption? =
        wire?.let { PlainWaterType.fromWireValue(it) }?.let { plainType ->
            when (plainType) {
                PlainWaterType.FILTERED -> DrinkWaterOption.STANDARD
                PlainWaterType.COLD -> DrinkWaterOption.COLD
                PlainWaterType.SPARKLING -> DrinkWaterOption.SPARK
            }
        }

    fun resolvePourType(
        clientId: String,
        serverPlainWaterType: String?,
        localPrefsJson: String?,
        subscriptionActive: Boolean,
        coerceEntitlement: Boolean,
    ): FlowWaterPourType {
        val preferred =
            serverPlainWaterType?.takeIf { it.isNotBlank() }
                ?: readLocalPref(localPrefsJson, clientId)
        val baseOption = wireToDrinkWaterOption(preferred) ?: DrinkWaterOption.COLD
        val option =
            if (coerceEntitlement) {
                PlainWaterEntitlement.coerceWaterOption(baseOption, subscriptionActive)
            } else {
                baseOption
            }
        return option.toFlowWaterPourType()
    }

    fun encodeLocalPrefs(
        currentJson: String?,
        clientId: String,
        option: DrinkWaterOption,
    ): String {
        val prefs = decodeLocalPrefs(currentJson).toMutableMap()
        prefs[clientId] = PlainWaterType.fromDrinkWaterOption(option).wireValue
        return json.encodeToString(LocalPrefs.serializer(), LocalPrefs(prefs))
    }

    private fun readLocalPref(localPrefsJson: String?, clientId: String): String? =
        decodeLocalPrefs(localPrefsJson)[clientId]

    private fun decodeLocalPrefs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(LocalPrefs.serializer(), raw).byClientId
        }.getOrDefault(emptyMap())
    }

    @Serializable
    private data class LocalPrefs(val byClientId: Map<String, String> = emptyMap())
}
