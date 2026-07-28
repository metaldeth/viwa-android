package com.viwa.android.hardware.controller

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import timber.log.Timber

/**
 * *
 * Накопленный расход в [JsonStoreKeys.WATER_USAGE_ML] пополняется только через
 * [accumulateHardwareReadingAfterSuccessfulPreparation] после успешной готовки (чтение + сброс на контроллере).
 */
@Singleton
class ViwaWaterCounterService
@Inject
constructor(
    private val hardware: ControllerHardwareManager,
    private val configRepository: ConfigRepository,
) {
    private var holdPourBaselineMl: Int? = null

    suspend fun getWaterUsageMl(): Int {
        val ml = readCounterMlWithoutReset()
        if (ml > 0) {
            hardware.sendCommand(RequestCommand.ResetWaterCounter, ControllerConstants.DEFAULT_BODY)
        }
        return ml
    }

    /** Baseline for subscription hold-to-pour delta measurement. */
    suspend fun beginHoldPourSession() {
        holdPourBaselineMl = readCounterMlWithoutReset()
    }

    suspend fun endHoldPourSessionAndReset(): Int {
        val baseline = holdPourBaselineMl ?: return 0
        holdPourBaselineMl = null
        val after = readCounterMlWithoutReset()
        val delta = (after - baseline).coerceAtLeast(0)
        if (delta > 0) {
            hardware.sendCommand(RequestCommand.ResetWaterCounter, ControllerConstants.DEFAULT_BODY)
            val current = configRepository.get(JsonStoreKeys.WATER_USAGE_ML)?.toDoubleOrNull() ?: 0.0
            configRepository.set(JsonStoreKeys.WATER_USAGE_ML, (current + delta).toString())
            Timber.tag(TAG).i("hold pour +%d ml → total %.1f ml", delta, current + delta)
        }
        return delta
    }

    fun cancelHoldPourSession() {
        holdPourBaselineMl = null
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

    private fun decodeCounterPayload(payload: ByteArray): Int =
        if (payload.size >= 2) {
            ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        } else {
            0
        }

 /**
 * Вызывать после каждой успешной готовки: считать мл с контроллера (и сбросить его счётчик),
 * прибавить к [JsonStoreKeys.WATER_USAGE_ML].
 * @return прочитанное приращение, мл
 */
    suspend fun accumulateHardwareReadingAfterSuccessfulPreparation(): Int {
        val delta = getWaterUsageMl()
        if (delta <= 0) return 0
        val current = configRepository.get(JsonStoreKeys.WATER_USAGE_ML)?.toDoubleOrNull() ?: 0.0
        val next = current + delta
        configRepository.set(JsonStoreKeys.WATER_USAGE_ML, next.toString())
        Timber.tag(TAG).i("water usage +%d ml → total %.1f ml", delta, next)
        return delta
    }

    suspend fun getAccumulatedWaterUsageMl(): Double =
        configRepository.get(JsonStoreKeys.WATER_USAGE_ML)?.toDoubleOrNull() ?: 0.0

    suspend fun resetWaterUsage() {
        hardware.sendCommand(RequestCommand.ResetWaterCounter, ControllerConstants.DEFAULT_BODY)
    }

    companion object {
        private const val TAG = "ViwaWaterCounter"
    }
}
