package com.viwa.android.domain.telemetry

import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainWaterEntitlementTest {
    @Test
    fun `premium types require active subscription`() {
        assertFalse(PlainWaterEntitlement.premiumTypesEnabled(subscriptionActive = false))
        assertTrue(PlainWaterEntitlement.premiumTypesEnabled(subscriptionActive = true))
    }

    @Test
    fun `inactive subscription coerces cold and sparkling to filtered`() {
        assertEquals(
            FlowWaterPourType.Filtered,
            PlainWaterEntitlement.effectivePourType(FlowWaterPourType.Cold, subscriptionActive = false),
        )
        assertEquals(
            FlowWaterPourType.Filtered,
            PlainWaterEntitlement.effectivePourType(FlowWaterPourType.Sparkling, subscriptionActive = false),
        )
        assertEquals(
            FlowWaterPourType.Filtered,
            PlainWaterEntitlement.effectivePourType(FlowWaterPourType.Filtered, subscriptionActive = false),
        )
    }

    @Test
    fun `active subscription preserves selected premium type`() {
        assertEquals(
            FlowWaterPourType.Cold,
            PlainWaterEntitlement.effectivePourType(FlowWaterPourType.Cold, subscriptionActive = true),
        )
        assertEquals(
            FlowWaterPourType.Sparkling,
            PlainWaterEntitlement.effectivePourType(FlowWaterPourType.Sparkling, subscriptionActive = true),
        )
    }

    @Test
    fun `coerceWaterOption maps premium options to standard when inactive`() {
        assertEquals(
            DrinkWaterOption.STANDARD,
            PlainWaterEntitlement.coerceWaterOption(DrinkWaterOption.COLD, subscriptionActive = false),
        )
        assertEquals(
            DrinkWaterOption.STANDARD,
            PlainWaterEntitlement.coerceWaterOption(DrinkWaterOption.SPARK, subscriptionActive = false),
        )
    }
}
