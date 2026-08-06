package com.viwa.android.hardware.controller

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.outbox.WaterUsageOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.telemetry.WaterUsageReportSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import timber.log.Timber

/**
 * Hardware water counter → immutable local lifetime total in [JsonStoreKeys.WATER_USAGE_ML].
 *
 * All hardware read / accumulate / reset paths and hold baseline state are serialized by [counterMutex]
 * so concurrent prep, hold, and service calls cannot cross-match [ResponseCommand.WaterCounterAnswer].
 */
@Singleton
class ViwaWaterCounterService
@Inject
constructor(
    private val hardware: ControllerHardwareManager,
    private val configRepository: ConfigRepository,
    private val waterUsageOutboxStore: WaterUsageOutboxStore,
    private val outboxDrainCoordinator: MachineOutboxDrainCoordinator,
) {
    private val counterMutex = Mutex()
    private var holdPourBaselineMl: Int? = null

    data class AccumulateResult(
        val deltaMl: Int,
        val lifetimeTotalMl: Double,
        val controllerResetSent: Boolean,
    )

    /** Baseline for subscription hold-to-pour delta measurement; flushes pre-existing controller volume first. */
    suspend fun beginHoldPourSession() {
        counterMutex.withLock {
            val preExisting = readCounterMlWithoutReset()
            if (preExisting > 0) {
                accumulateDeltaToLifetime(preExisting)
                resetControllerCounter()
            }
            holdPourBaselineMl = 0
        }
    }

    suspend fun endHoldPourSessionAndReset(): Int =
        counterMutex.withLock {
            val baseline = holdPourBaselineMl ?: return@withLock 0
            holdPourBaselineMl = null
            val after = readCounterMlWithoutReset()
            val delta = (after - baseline).coerceAtLeast(0)
            if (delta > 0) {
                accumulateDeltaToLifetime(delta)
                Timber.tag(TAG).i("hold pour +%d ml", delta)
            }
            resetControllerCounter()
            delta
        }

    suspend fun cancelHoldPourSession() {
        counterMutex.withLock {
            holdPourBaselineMl = null
        }
    }

    /** After successful drink preparation: read controller, add to lifetime, reset controller. */
    suspend fun accumulateHardwareReadingAfterSuccessfulPreparation(): Int =
        readAccumulateAndResetController().deltaMl

    /**
     * Service operator path: read controller, add delta to lifetime when > 0, always reset controller.
     * Lifetime total is never cleared.
     */
    suspend fun readAccumulateAndResetController(): AccumulateResult =
        counterMutex.withLock {
            val delta = readCounterMlWithoutReset()
            val lifetimeTotalMl =
                if (delta > 0) {
                    accumulateDeltaToLifetime(delta).also {
                        Timber.tag(TAG).i("water usage +%d ml → total %.1f ml", delta, it)
                    }
                } else {
                    readAccumulatedWaterUsageMl()
                }
            resetControllerCounter()
            AccumulateResult(
                deltaMl = delta,
                lifetimeTotalMl = lifetimeTotalMl,
                controllerResetSent = true,
            )
        }

    /** Alias for operator reset — always sends controller reset even when delta is 0. */
    suspend fun resetControllerAfterAccumulating(): AccumulateResult = readAccumulateAndResetController()

    suspend fun getAccumulatedWaterUsageMl(): Double = readAccumulatedWaterUsageMl()

    private suspend fun readAccumulatedWaterUsageMl(): Double =
        configRepository.get(JsonStoreKeys.WATER_USAGE_ML)?.toDoubleOrNull() ?: 0.0

    private suspend fun accumulateDeltaToLifetime(delta: Int): Double {
        require(delta > 0) { "delta must be positive" }
        val current = readAccumulatedWaterUsageMl()
        val next = current + delta
        configRepository.set(JsonStoreKeys.WATER_USAGE_ML, next.toString())
        enqueueAbsoluteWaterUsageReport(next.roundToInt())
        return next
    }

    private suspend fun enqueueAbsoluteWaterUsageReport(totalMl: Int) {
        val reportedAt = TelemetryIsoTimestamps.nowUtc()
        runCatching {
            waterUsageOutboxStore.enqueueWaterUsageReport(
                WaterUsageReportSnapshot(totalMl = totalMl, reportedAt = reportedAt),
            )
            outboxDrainCoordinator.onEnqueue()
        }.onFailure { Timber.tag(TAG).w(it, "enqueue machine.water.usage.report failed totalMl=$totalMl") }
    }

    private suspend fun readCounterMlWithoutReset(): Int =
        coroutineScope {
            val awaitAnswer =
                async {
                    hardware.incomingResponses.first {
                        it.response == ResponseCommand.WaterCounterAnswer
                    }
                }
            yield()
            hardware.sendCommand(RequestCommand.ReadWaterCounter, ControllerConstants.DEFAULT_BODY)
            val answer =
                withTimeoutOrNull(ControllerConstants.WATER_COUNTER_TIMEOUT_MS) {
                    awaitAnswer.await()
                } ?: return@coroutineScope 0
            decodeCounterPayload(answer.payload)
        }

    private suspend fun resetControllerCounter() {
        hardware.sendCommand(RequestCommand.ResetWaterCounter, ControllerConstants.DEFAULT_BODY)
    }

    private fun decodeCounterPayload(payload: ByteArray): Int =
        if (payload.size >= 2) {
            ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        } else {
            0
        }

    companion object {
        private const val TAG = "ViwaWaterCounter"
    }
}
