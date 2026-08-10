package com.viwa.android.domain.telemetry

import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.model.customer.toFlowWaterPourType
import kotlinx.serialization.Serializable

/** Wire `pourKind` for `telemetry.pour.report`. */
enum class PourKind(val wireValue: String) {
    FLAVORED("FLAVORED"),
    PLAIN_WATER("PLAIN_WATER"),
}

/** Wire `plainWaterType` for unlimited plain-water analytics. */
enum class PlainWaterType(val wireValue: String) {
    FILTERED("FILTERED"),
    COLD("COLD"),
    SPARKLING("SPARKLING"),
    ;

    companion object {
        fun fromFlowWaterPourType(type: FlowWaterPourType): PlainWaterType =
            when (type) {
                FlowWaterPourType.Filtered -> FILTERED
                FlowWaterPourType.Cold -> COLD
                FlowWaterPourType.Sparkling -> SPARKLING
            }

        fun fromDrinkWaterOption(option: DrinkWaterOption): PlainWaterType =
            fromFlowWaterPourType(option.toFlowWaterPourType())
    }
}

/** Wire `strength` for flavored pours and paid complete. */
enum class DrinkStrength(val wireValue: String, val canonicalRatio: Double) {
    WEAK("WEAK", 0.9),
    STANDARD("STANDARD", 1.0),
    STRONG("STRONG", 1.1),
    ;

    companion object {
        fun fromConcentration(concentration: DrinkConcentration): DrinkStrength =
            when (concentration) {
                DrinkConcentration.Weak -> WEAK
                DrinkConcentration.Standard -> STANDARD
                DrinkConcentration.Strong -> STRONG
            }
    }
}

fun DrinkConcentration.toDrinkStrength(): DrinkStrength = DrinkStrength.fromConcentration(this)

/** Subscription pour — `telemetry.pour.report` payload (no grantId). */
@Serializable
data class PourEventSnapshot(
    val requestUuid: String,
    val pouredAt: String,
    val volumeMl: Int,
    val pourKind: String,
    val clientId: String? = null,
    val plainWaterType: String? = null,
    val productId: String? = null,
    val productNameSnapshot: String? = null,
    val strength: String? = null,
    val strengthRatio: Double? = null,
    val syrupMlActual: Int? = null,
    val recipeDrinkVolumeMl: Int? = null,
    val recipeWaterMl: Double? = null,
    val recipeProductMl: Double? = null,
    val conversionFactor: Double? = null,
)

/** Paid beverage — flat `telemetry.paid.complete` atomic payload. */
@Serializable
data class PaidCompleteSnapshot(
    val transactionId: String,
    val requestUuid: String,
    val occurredAt: String,
    val productId: String,
    val productNameSnapshot: String,
    val volumeMl: Int,
    val strength: String,
    val strengthRatio: Double,
    val syrupMlActual: Int,
    val amountKopecks: Int,
    val payMethod: String,
    val recipeDrinkVolumeMl: Int? = null,
    val recipeWaterMl: Double? = null,
    val recipeProductMl: Double? = null,
    val conversionFactor: Double? = null,
    val plainWaterType: String? = null,
)
