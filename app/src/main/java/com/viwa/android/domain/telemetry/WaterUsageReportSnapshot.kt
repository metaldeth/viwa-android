package com.viwa.android.domain.telemetry

import kotlinx.serialization.Serializable

/** Wire payload for `machine.water.usage.report` — absolute lifetime total, not a delta. */
@Serializable
data class WaterUsageReportSnapshot(
    val totalMl: Int,
    val reportedAt: String,
)
