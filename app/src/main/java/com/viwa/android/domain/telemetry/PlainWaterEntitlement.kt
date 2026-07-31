package com.viwa.android.domain.telemetry

import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.model.customer.toDrinkWaterOption
import com.viwa.android.domain.model.customer.toFlowWaterPourType

/** Subscription gating for hold-to-pour plain water (FILTERED free; COLD/SPARKLING require active sub). */
object PlainWaterEntitlement {
    fun effectivePourType(
        selected: FlowWaterPourType,
        subscriptionActive: Boolean,
    ): FlowWaterPourType =
        if (subscriptionActive || selected == FlowWaterPourType.Filtered) {
            selected
        } else {
            FlowWaterPourType.Filtered
        }

    fun coerceWaterOption(
        option: DrinkWaterOption,
        subscriptionActive: Boolean,
    ): DrinkWaterOption = effectivePourType(option.toFlowWaterPourType(), subscriptionActive).toDrinkWaterOption()

    fun premiumTypesEnabled(subscriptionActive: Boolean): Boolean = subscriptionActive
}
