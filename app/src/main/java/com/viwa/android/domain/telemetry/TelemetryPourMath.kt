package com.viwa.android.domain.telemetry

import kotlin.math.roundToInt

/**
 * Integer syrup ml for telemetry, based on canonical recipe template
 * (`dosage.product` at `recipeDrinkVolumeMl`, default 30 ml @ 300 ml) and strength ratio.
 */
object TelemetryPourMath {
    fun syrupMlActual(
        dosageProduct: Double,
        recipeDrinkVolumeMl: Int,
        volumeMl: Int,
        strengthRatio: Double,
    ): Int {
        if (recipeDrinkVolumeMl <= 0 || volumeMl <= 0) return 0
        return (dosageProduct * (volumeMl.toDouble() / recipeDrinkVolumeMl) * strengthRatio).roundToInt()
    }
}
