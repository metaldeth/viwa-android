package com.viwa.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WaterCalibrationData(
 /** Текущая рабочая скорость для расчёта времени (с учётом авто-адаптации). */
    val flowRateMlPerSec: Double? = null,
 /** Скорость, полученная при ручной калибровке воды (опорное значение). */
    val calibratedFlowRateMlPerSec: Double? = null,
 /** Сглаженная динамическая скорость по последним фактическим готовкам (EMA). */
    val adaptiveFlowRateMlPerSec: Double? = null,
 /** Последняя мгновенная наблюдаемая скорость по факту готовки. */
    val lastObservedFlowRateMlPerSec: Double? = null,
 /** Время последнего обновления adaptiveFlowRateMlPerSec. */
    val lastAdaptiveUpdateTimestampMs: Long? = null,
    val lastTargetMl: Int? = null,
    val lastActualMl: Int? = null,
    val lastPourDurationSec: Double? = null,
    val lastPourTimestampMs: Long? = null,
    val lastSodaTargetMl: Int? = null,
    val lastSodaActualMl: Int? = null,
    val lastSodaPourDurationSec: Double? = null,
    val lastSodaPourTimestampMs: Long? = null,
    /** Last known water pump coefficient (tenths), persisted for offline fallback. */
    val waterPumpTenths: Int? = null,
    /** Last known sparkling-water pump coefficient (tenths), 0xBB `buff_rx[3]`. */
    val sodaPumpTenths: Int? = null,
)
