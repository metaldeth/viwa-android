package com.viwa.android.services.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaterCalibrationCalculationsTest {

    @Test
    fun computeNewTenths_scalesAndClamps() {
        assertEquals(125, WaterCalibrationCalculations.computeNewTenths(100, 200.0, 160.0))
        assertEquals(1, WaterCalibrationCalculations.computeNewTenths(1, 1.0, 1000.0))
        assertEquals(255, WaterCalibrationCalculations.computeNewTenths(200, 500.0, 10.0))
    }

    @Test
    fun computeFlowRateMlPerSec_dividesActualByDuration() {
        assertEquals(20.0, WaterCalibrationCalculations.computeFlowRateMlPerSec(100.0, 5.0)!!, 0.001)
        assertNull(WaterCalibrationCalculations.computeFlowRateMlPerSec(100.0, 0.0))
        assertNull(WaterCalibrationCalculations.computeFlowRateMlPerSec(100.0, -1.0))
    }

    @Test
    fun computeAdaptiveFlowRateMlPerSec_emaAndClamp() {
        val blended =
            WaterCalibrationCalculations.computeAdaptiveFlowRateMlPerSec(
                currentFlowRateMlPerSec = 20.0,
                observedFlowRateMlPerSec = 30.0,
                windowSize = 2,
            )
        assertEquals(25.0, blended!!, 0.001)

        val bootstrap =
            WaterCalibrationCalculations.computeAdaptiveFlowRateMlPerSec(
                currentFlowRateMlPerSec = null,
                observedFlowRateMlPerSec = 28.0,
                windowSize = 2,
            )
        assertEquals(28.0, bootstrap!!, 0.001)

        assertNull(
            WaterCalibrationCalculations.computeAdaptiveFlowRateMlPerSec(
                currentFlowRateMlPerSec = 20.0,
                observedFlowRateMlPerSec = 0.5,
                windowSize = 2,
            ),
        )
    }

    @Test
    fun resolveEffectiveFlowRate_prefersLocalThenTelemetryBaseline() {
        assertEquals(
            27.5,
            WaterCalibrationCalculations.resolveEffectiveFlowRateMlPerSec(
                localFlowRateMlPerSec = 27.5,
                telemetryWaterPumpTenths = 180,
            )!!,
            0.001,
        )
        assertEquals(
            20.0,
            WaterCalibrationCalculations.resolveEffectiveFlowRateMlPerSec(
                localFlowRateMlPerSec = null,
                telemetryWaterPumpTenths = 180,
            )!!,
            0.001,
        )
        assertNull(
            WaterCalibrationCalculations.resolveEffectiveFlowRateMlPerSec(
                localFlowRateMlPerSec = null,
                telemetryWaterPumpTenths = null,
            ),
        )
    }
}
