package com.viwa.android.domain.model

import kotlinx.serialization.Serializable

/** Machine-level calibration synced with telemetry (water + soda pump tenths). */
@Serializable
data class MachineCalibration(
    val waterPumpTenths: Int,
    val sodaPumpTenths: Int,
)
