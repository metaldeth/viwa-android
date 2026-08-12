package com.viwa.android.domain.telemetry

import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import org.junit.Assert.assertEquals
import org.junit.Test

class LoyaltyPlainWaterPreferenceTest {
    @Test
    fun resolvePourType_defaultsToColdWithoutHistory() {
        val pourType =
            LoyaltyPlainWaterPreference.resolvePourType(
                clientId = "client-1",
                serverPlainWaterType = null,
                localPrefsJson = null,
                subscriptionActive = true,
                coerceEntitlement = true,
            )

        assertEquals(FlowWaterPourType.Cold, pourType)
    }

    @Test
    fun resolvePourType_prefersServerOverLocalCache() {
        val localJson =
            LoyaltyPlainWaterPreference.encodeLocalPrefs(
                currentJson = null,
                clientId = "client-1",
                option = DrinkWaterOption.COLD,
            )
        val pourType =
            LoyaltyPlainWaterPreference.resolvePourType(
                clientId = "client-1",
                serverPlainWaterType = "SPARKLING",
                localPrefsJson = localJson,
                subscriptionActive = true,
                coerceEntitlement = true,
            )

        assertEquals(FlowWaterPourType.Sparkling, pourType)
    }

    @Test
    fun resolvePourType_mapsFilteredToStandard() {
        val pourType =
            LoyaltyPlainWaterPreference.resolvePourType(
                clientId = "client-1",
                serverPlainWaterType = "FILTERED",
                localPrefsJson = null,
                subscriptionActive = true,
                coerceEntitlement = true,
            )

        assertEquals(FlowWaterPourType.Filtered, pourType)
    }

    @Test
    fun resolvePourType_coercesPremiumWithoutSubscription() {
        val pourType =
            LoyaltyPlainWaterPreference.resolvePourType(
                clientId = "client-1",
                serverPlainWaterType = "COLD",
                localPrefsJson = null,
                subscriptionActive = false,
                coerceEntitlement = true,
            )

        assertEquals(FlowWaterPourType.Filtered, pourType)
    }

    @Test
    fun encodeLocalPrefs_roundTripsClientChoice() {
        val encoded =
            LoyaltyPlainWaterPreference.encodeLocalPrefs(
                currentJson = null,
                clientId = "client-1",
                option = DrinkWaterOption.SPARK,
            )

        val pourType =
            LoyaltyPlainWaterPreference.resolvePourType(
                clientId = "client-1",
                serverPlainWaterType = null,
                localPrefsJson = encoded,
                subscriptionActive = true,
                coerceEntitlement = true,
            )

        assertEquals(FlowWaterPourType.Sparkling, pourType)
    }
}
