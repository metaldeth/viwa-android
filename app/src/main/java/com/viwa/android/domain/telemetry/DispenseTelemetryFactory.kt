package com.viwa.android.domain.telemetry

import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.toRatio
import java.util.UUID
import kotlin.math.roundToInt

object DispenseTelemetryFactory {
    fun flavoredPourEvent(
        requestUuid: String,
        volumeMl: Int,
        productId: String,
        productNameSnapshot: String,
        concentration: DrinkConcentration,
        dosage: DrinkDosage,
        clientId: String,
        pouredAt: String = TelemetryIsoTimestamps.nowUtc(),
    ): PourEventSnapshot {
        val strength = concentration.toDrinkStrength()
        val ratio = concentration.toRatio()
        return PourEventSnapshot(
            requestUuid = requestUuid,
            pouredAt = pouredAt,
            volumeMl = volumeMl,
            pourKind = PourKind.FLAVORED.wireValue,
            clientId = clientId,
            productId = productId,
            productNameSnapshot = productNameSnapshot,
            strength = strength.wireValue,
            strengthRatio = strength.canonicalRatio,
            syrupMlActual =
                TelemetryPourMath.syrupMlActual(
                    dosageProduct = dosage.product,
                    recipeDrinkVolumeMl = dosage.drinkVolume,
                    volumeMl = volumeMl,
                    strengthRatio = ratio,
                ),
        )
    }

    fun plainPourEvent(
        requestUuid: String,
        volumeMl: Int,
        clientId: String,
        plainWaterType: PlainWaterType,
        pouredAt: String = TelemetryIsoTimestamps.nowUtc(),
    ): PourEventSnapshot =
        PourEventSnapshot(
            requestUuid = requestUuid,
            pouredAt = pouredAt,
            volumeMl = volumeMl,
            pourKind = PourKind.PLAIN_WATER.wireValue,
            clientId = clientId,
            plainWaterType = plainWaterType.wireValue,
        )

    fun paidComplete(
        transactionId: String,
        requestUuid: String,
        volumeMl: Int,
        amountRub: Double,
        payMethod: String,
        productId: String,
        productNameSnapshot: String,
        concentration: DrinkConcentration,
        dosage: DrinkDosage,
        occurredAt: String = TelemetryIsoTimestamps.nowUtc(),
    ): PaidCompleteSnapshot {
        require(volumeMl == 300 || volumeMl == 700) { "Paid volume must be 300 or 700 ml, got $volumeMl" }
        val normalizedPayMethod = payMethod.uppercase()
        require(normalizedPayMethod in PAID_PAY_METHODS) {
            "Paid payMethod must be one of $PAID_PAY_METHODS, got $payMethod"
        }
        val strength = concentration.toDrinkStrength()
        val ratio = concentration.toRatio()
        val syrupMlActual =
            TelemetryPourMath.syrupMlActual(
                dosageProduct = dosage.product,
                recipeDrinkVolumeMl = dosage.drinkVolume,
                volumeMl = volumeMl,
                strengthRatio = ratio,
            )
        val amountKopecks = (amountRub * 100.0).roundToInt().coerceAtLeast(1)
        return PaidCompleteSnapshot(
            transactionId = transactionId,
            requestUuid = requestUuid,
            occurredAt = occurredAt,
            productId = productId,
            productNameSnapshot = productNameSnapshot,
            volumeMl = volumeMl,
            strength = strength.wireValue,
            strengthRatio = strength.canonicalRatio,
            syrupMlActual = syrupMlActual,
            amountKopecks = amountKopecks,
            payMethod = normalizedPayMethod,
        )
    }

    fun newStableUuid(): String = UUID.randomUUID().toString()

    private val PAID_PAY_METHODS = setOf("CASH", "CARD", "SBP", "OTHER")
}
