package com.viwa.android.services.calibration

import kotlin.math.roundToInt

/**
 * Чистая логика для unit-тестов.
 */
object WaterCalibrationCalculations {
    const val MIN_POUR_DURATION_SEC = 1.0
    const val DEFAULT_ADAPTIVE_WINDOW_SIZE = 2
    const val MIN_ADAPTIVE_WINDOW_SIZE = 1
    const val MAX_ADAPTIVE_WINDOW_SIZE = 20
    const val MIN_VALID_FLOW_RATE_ML_PER_SEC = 5.0
    const val MAX_VALID_FLOW_RATE_ML_PER_SEC = 80.0
    /**
     * Базовая скорость из эталонного FLOW. Используется только когда telemetry snapshot
     * подтверждает машинную калибровку waterPumpTenths; после готовки уточняется по факту.
     */
    const val TELEMETRY_BASELINE_FLOW_RATE_ML_PER_SEC = 20.0

    fun computeNewTenths(
        currentTenths: Int,
        targetVolumeMl: Double,
        actualVolumeMl: Double,
    ): Int {
        if (actualVolumeMl <= 0) return currentTenths.coerceIn(1, 255)
        val raw = (currentTenths * targetVolumeMl / actualVolumeMl).roundToInt()
        return raw.coerceIn(1, 255)
    }

    fun computeFlowRateMlPerSec(
        actualVolumeMl: Double,
        lastPourDurationSec: Double,
    ): Double? {
        if (lastPourDurationSec <= 0) return null
        return actualVolumeMl / lastPourDurationSec
    }

    fun computeAdaptiveFlowRateMlPerSec(
        currentFlowRateMlPerSec: Double?,
        observedFlowRateMlPerSec: Double?,
        windowSize: Int = DEFAULT_ADAPTIVE_WINDOW_SIZE,
    ): Double? {
        val observed = sanitizeFlowRate(observedFlowRateMlPerSec) ?: return null
        val current = sanitizeFlowRate(currentFlowRateMlPerSec)
        val n = windowSize.coerceIn(MIN_ADAPTIVE_WINDOW_SIZE, MAX_ADAPTIVE_WINDOW_SIZE).toDouble()
        val blended =
            if (current == null) {
                observed
            } else {
 // Эквивалент EMA c alpha=1/N: при N=2 адаптация за 1-2 налива.
                current + (observed - current) / n
            }
        return sanitizeFlowRate(blended)
    }

    fun sanitizeFlowRate(value: Double?): Double? {
        if (value == null || !value.isFinite()) return null
        if (value < MIN_VALID_FLOW_RATE_ML_PER_SEC || value > MAX_VALID_FLOW_RATE_ML_PER_SEC) {
            return null
        }
        return value
    }

    fun resolveEffectiveFlowRateMlPerSec(
        localFlowRateMlPerSec: Double?,
        telemetryWaterPumpTenths: Int?,
    ): Double? =
        sanitizeFlowRate(localFlowRateMlPerSec)
            ?: TELEMETRY_BASELINE_FLOW_RATE_ML_PER_SEC.takeIf {
                telemetryWaterPumpTenths != null && telemetryWaterPumpTenths in 1..255
            }
}
