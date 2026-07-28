package com.viwa.android.domain.telemetry

import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.offline.OfflinePourTransactionCoordinator
import com.viwa.android.hardware.controller.ViwaWaterCounterService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Subscription hold-to-pour telemetry: measure hardware delta and emit exactly one [PourEventSnapshot].
 */
@Singleton
class HoldPourTelemetryCoordinator
@Inject
constructor(
    private val waterCounter: ViwaWaterCounterService,
    private val dispenseSyncCoordinator: TelemetryDispenseSyncCoordinator,
    private val offlinePourCoordinator: OfflinePourTransactionCoordinator,
) {
    private var activeRequestUuid: String? = null
    private var activeClientId: String? = null
    private var activeMachineId: String? = null
    private var activePlainWaterType: FlowWaterPourType = FlowWaterPourType.Filtered
    private var offlineMode: Boolean = false

    suspend fun beginHoldPourSession(
        clientId: String,
        machineId: String,
        plainWaterType: FlowWaterPourType,
        offlineMode: Boolean,
        requestUuid: String = UUID.randomUUID().toString(),
    ): String {
        activeRequestUuid = requestUuid
        activeClientId = clientId
        activeMachineId = machineId
        activePlainWaterType = plainWaterType
        this.offlineMode = offlineMode
        waterCounter.beginHoldPourSession()
        if (offlineMode) {
            when (
                offlinePourCoordinator.reservePour(
                    clientId = clientId,
                    machineId = machineId,
                    volumeMl = 1,
                    drinkId = null,
                    saleId = requestUuid,
                    requestUuid = requestUuid,
                )
            ) {
                is OfflinePourTransactionCoordinator.ReservePourResult.Denied -> {
                    Timber.tag(TAG).w("hold pour offline reserve denied")
                }
                else -> offlinePourCoordinator.markPouring(requestUuid)
            }
        }
        return requestUuid
    }

    suspend fun finalizeHoldPourSession(): Int {
        val requestUuid = activeRequestUuid ?: return 0
        val clientId = activeClientId ?: return clearAndReturn(0)
        val machineId = activeMachineId
        val plainType = activePlainWaterType
        val measuredMl = waterCounter.endHoldPourSessionAndReset()
        clearSessionLocals()
        if (measuredMl <= 0) {
            if (offlineMode) {
                offlinePourCoordinator.finalizePour(requestUuid, 0)
            }
            return 0
        }
        if (offlineMode && machineId != null) {
            offlinePourCoordinator.finalizePour(requestUuid, measuredMl)
        }
        if (!offlineMode) {
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
        } else {
            offlinePourCoordinator.enqueueForSync(requestUuid, clientId, isFree = true)
        }
        return measuredMl
    }

    fun cancelHoldPourSession() {
        activeRequestUuid = null
        activeClientId = null
        activeMachineId = null
        waterCounter.cancelHoldPourSession()
    }

    private fun clearSessionLocals() {
        activeRequestUuid = null
        activeClientId = null
        activeMachineId = null
        offlineMode = false
    }

    private fun clearAndReturn(value: Int): Int {
        clearSessionLocals()
        waterCounter.cancelHoldPourSession()
        return value
    }

    companion object {
        private const val TAG = "HoldPourTelemetry"
    }
}
