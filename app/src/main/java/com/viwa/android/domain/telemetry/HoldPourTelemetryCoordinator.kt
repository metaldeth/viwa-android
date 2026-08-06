package com.viwa.android.domain.telemetry

import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.hardware.controller.ViwaWaterCounterService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Hold-to-pour plain water: measure hardware delta and emit exactly one [PourEventSnapshot]
 * via durable machine outbox (online and offline). Anonymous FILTERED allowed (`clientId` null).
 */
@Singleton
class HoldPourTelemetryCoordinator
@Inject
constructor(
    private val waterCounter: ViwaWaterCounterService,
    private val dispenseSyncCoordinator: TelemetryDispenseSyncCoordinator,
) {
    private var activeRequestUuid: String? = null
    private var activeClientId: String? = null
    private var activePlainWaterType: FlowWaterPourType = FlowWaterPourType.Filtered

    suspend fun beginHoldPourSession(
        clientId: String?,
        machineId: String,
        plainWaterType: FlowWaterPourType,
        offlineMode: Boolean,
        requestUuid: String = UUID.randomUUID().toString(),
    ): String {
        activeRequestUuid = requestUuid
        activeClientId = clientId
        activePlainWaterType = plainWaterType
        waterCounter.beginHoldPourSession()
        if (offlineMode) {
            Timber.tag(TAG).d("hold pour offline — machine outbox only requestUuid=$requestUuid")
        }
        return requestUuid
    }

    suspend fun finalizeHoldPourSession(): Int {
        val requestUuid = activeRequestUuid ?: return 0
        val clientId = activeClientId
        val plainType = activePlainWaterType
        val measuredMl = waterCounter.endHoldPourSessionAndReset()
        clearSessionLocals()
        if (measuredMl <= 0) {
            return 0
        }
        val pour =
            PourEventSnapshot(
                requestUuid = requestUuid,
                pouredAt = TelemetryIsoTimestamps.nowUtc(),
                volumeMl = measuredMl,
                pourKind = PourKind.PLAIN_WATER.wireValue,
                clientId = clientId,
                plainWaterType = PlainWaterType.fromFlowWaterPourType(plainType).wireValue,
            )
        runCatching {
            dispenseSyncCoordinator.enqueuePourReport(pour)
        }.onFailure { Timber.tag(TAG).e(it, "enqueue hold pour failed requestUuid=$requestUuid") }
        return measuredMl
    }

    suspend fun cancelHoldPourSession() {
        activeRequestUuid = null
        activeClientId = null
        waterCounter.cancelHoldPourSession()
    }

    private fun clearSessionLocals() {
        activeRequestUuid = null
        activeClientId = null
    }

    private suspend fun clearAndReturn(value: Int): Int {
        clearSessionLocals()
        waterCounter.cancelHoldPourSession()
        return value
    }

    companion object {
        private const val TAG = "HoldPourTelemetry"
    }
}
