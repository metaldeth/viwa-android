package com.viwa.android.services.preparing

import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.preparing.PreparingState
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.ViwaWaterCounterService
import com.viwa.android.hardware.controller.ResponseCommand
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapter
import com.viwa.android.services.calibration.WaterCalibrationService
import com.viwa.android.services.inventory.InventoryService
import com.viwa.android.services.controller.ViwaControllerStateService
import com.viwa.android.services.drink.DrinkPreparationCalculations
import com.viwa.android.services.drink.ViwaDrinkPreparingService
import com.viwa.android.services.drink.ViwaDrinkSelectionService
import com.viwa.android.hardware.FlowStripRgbCoordinator
import com.viwa.android.data.local.sales.PendingSale
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.remote.telemetry.mvp.TelemetrySalesSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.local.outbox.LoyaltyWaterOutboxStore
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.offline.OfflinePourTransactionCoordinator
import com.viwa.android.domain.offline.SubscriptionPourContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class PreparingManager
@Inject
constructor(
    private val drinkSelection: ViwaDrinkSelectionService,
    private val drinkPreparing: ViwaDrinkPreparingService,
    private val cellsRepository: TelemetryCellsRepository,
    private val waterCalibrationService: WaterCalibrationService,
    private val controllerState: ViwaControllerStateService,
    private val gateway: ControllerGateway,
    private val onStateChanged: PreparingStateCallback,
    private val inventoryService: InventoryService,
    private val preparingTimeHistoryStore: PreparingTimeHistoryStore,
    private val waterCounter: ViwaWaterCounterService,
    private val flowStripRgbCoordinator: FlowStripRgbCoordinator,
    private val salesSyncCoordinator: TelemetrySalesSyncCoordinator,
    private val offlinePourCoordinator: OfflinePourTransactionCoordinator,
    private val waterOutboxStore: LoyaltyWaterOutboxStore,
    private val outboxDrainCoordinator: MachineOutboxDrainCoordinator,
    @AppIoScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private var successWatchJob: Job? = null
    private var currentPreparingContext: CurrentPreparingContext? = null

    private val _customerPhase =
        MutableStateFlow<CustomerPreparingPhase>(CustomerPreparingPhase.Idle)
    val customerPhase: StateFlow<CustomerPreparingPhase> = _customerPhase.asStateFlow()

    fun resetSession() {
        successWatchJob?.cancel()
        successWatchJob = null
        drinkPreparing.cancelMockPreparing()
        currentPreparingContext = null
        _customerPhase.value = CustomerPreparingPhase.Idle
    }

 /**
 * Полный флоу: ensureAutoMode → контейнер → калибровка воды → ChooseDrink → 200 ms → StartDrinkPreparing.
 * @param tasteId [com.viwa.android.domain.model.customer.DrinkTaste.id].
 * @param saleTotalPriceRub Сумма списания с клиента при оплате терминалом; в free — 0
 * @param salePayMethod `CARD` / `SBP` / null в free
 */
    suspend fun prepareDrink(
        tasteId: Int,
        volumeMl: Int,
        waterOption: DrinkWaterOption = DrinkWaterOption.STANDARD,
        concentrationRatio: Double = 1.0,
        saleTotalPriceRub: Double = 0.0,
        salePayMethod: String? = null,
        subscriptionPourContext: SubscriptionPourContext? = null,
    ): PrepareDrinkResult =
        mutex.withLock {
            successWatchJob?.cancel()
            successWatchJob = null
            currentPreparingContext = null
            flowStripRgbCoordinator.cancelPendingGreenSchedule()
            emit(PreparingState.StartPreparing)

            try {
                controllerState.ensureAutoMode(timeoutMs = 5000L)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "ensureAutoMode failed")
                val msg = e.message ?: e.toString()
                val fail =
                    PreparingState.Fail(
                        errorCode = PreparingErrorCodes.AUTO_MODE_SWITCH_FAILED,
                        message = msg,
                    )
                emit(fail)
                return@withLock PrepareDrinkResult.Error(PreparingErrorCodes.AUTO_MODE_SWITCH_FAILED, msg)
            }

            val container =
                cellsRepository.getSnapshot()?.let { snapshot ->
                    TelemetryCellsSnapshotAdapter.toDrinkContainers(snapshot)
                        .find { it.product.taste.id == tasteId }
                }
                    ?: run {
                        val msg = "Контейнер для вкуса $tasteId не найден"
                        emit(PreparingState.Fail(PreparingErrorCodes.CONTAINER_NOT_FOUND, msg))
                        return@withLock PrepareDrinkResult.Error(PreparingErrorCodes.CONTAINER_NOT_FOUND, msg)
                    }

            val flowRate = waterCalibrationService.loadCalibration().flowRateMlPerSec
            if (flowRate == null || flowRate <= 0) {
                val msg = "Выполните калибровку воды в сервисном меню"
                emit(PreparingState.Fail(PreparingErrorCodes.WATER_NOT_CALIBRATED, msg))
                return@withLock PrepareDrinkResult.Error(PreparingErrorCodes.WATER_NOT_CALIBRATED, msg)
            }

            val preparingTime =
                drinkSelection.chooseDrink(
                    container = container,
                    drinkVolumeMl = volumeMl,
                    waterOption = waterOption,
                    concentrationRatio = concentrationRatio,
                    flowRateMlPerSec = flowRate,
                )
            val dosage = container.product.dosage
            val actualWaterMl =
                DrinkPreparationCalculations.waterMlForDrink(
                    dosageWaterMl = dosage.water,
                    drinkVolumeMl = volumeMl,
                    recipeDrinkVolumeMl = dosage.drinkVolume,
                )
            currentPreparingContext =
                CurrentPreparingContext(
                    startedAtMs = System.currentTimeMillis(),
                    startedAtMonotonicMs = android.os.SystemClock.elapsedRealtime(),
                    tasteId = container.product.taste.id,
                    drinkName = container.product.name,
                    containerNumber = container.containerNumber,
                    volumeMl = volumeMl,
                    recipeDrinkVolumeMl = dosage.drinkVolume,
                    recipeWaterMl = dosage.water,
                    actualWaterMl = actualWaterMl,
                    flowRateMlPerSec = flowRate,
                    expectedTimeSec = preparingTime,
                    saleTotalPriceRub = saleTotalPriceRub,
                    salePayMethod = salePayMethod,
                    concentrationRatio = concentrationRatio,
                    subscriptionPourContext = subscriptionPourContext,
                )

            if (subscriptionPourContext != null) {
                val reserve =
                    offlinePourCoordinator.reservePour(
                        clientId = subscriptionPourContext.clientId,
                        machineId = subscriptionPourContext.machineId,
                        volumeMl = volumeMl,
                        drinkId = container.product.taste.id,
                        saleId = subscriptionPourContext.saleId,
                        requestUuid = subscriptionPourContext.requestUuid,
                    )
                when (reserve) {
                    is OfflinePourTransactionCoordinator.ReservePourResult.Denied -> {
                        val msg = offlineDenyMessage(reserve.reason)
                        emit(PreparingState.Fail(PreparingErrorCodes.OFFLINE_ENTITLEMENT_DENIED, msg))
                        return@withLock PrepareDrinkResult.Error(PreparingErrorCodes.OFFLINE_ENTITLEMENT_DENIED, msg)
                    }
                    is OfflinePourTransactionCoordinator.ReservePourResult.AlreadyReserved -> Unit
                    is OfflinePourTransactionCoordinator.ReservePourResult.Reserved -> Unit
                }
            }

            emit(PreparingState.Begin(preparingTime))
            delay(200)

            _customerPhase.value = CustomerPreparingPhase.AwaitingDrinkReady(preparingTime)

            subscriptionPourContext?.let { ctx ->
                offlinePourCoordinator.markPouring(ctx.requestUuid)
            }

            successWatchJob =
                scope.launch {
                    try {
                        gateway.incomingResponses.first { it.response == ResponseCommand.DrinkPreparingSuccess }
                        inventoryService.applyWriteOff(container.containerNumber, volumeMl, concentrationRatio)
                        runCatching {
                            waterCounter.accumulateHardwareReadingAfterSuccessfulPreparation()
                        }.onFailure { Timber.tag(TAG).w(it, "water counter accumulate") }
                        emit(PreparingState.Success)
                        _customerPhase.value = CustomerPreparingPhase.DrinkReady
                        flowStripRgbCoordinator.scheduleGreenForTenSecondsThenRestoreSaved()
                        val pourContext = currentPreparingContext
                        persistPreparingTimeRecord()
                        enqueueSuccessfulSaleReport()
                        pourContext?.let {
                            finalizeSubscriptionPourIfNeeded(it.actualWaterMl.toInt().coerceAtLeast(1))
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "await DrinkPreparingSuccess")
                    } finally {
                        currentPreparingContext = null
                    }
                }

            drinkPreparing.startDrinkPreparing(preparingTime)

            return@withLock PrepareDrinkResult.Ok(preparingTime)
        }

    private fun emit(state: PreparingState) {
        onStateChanged(state)
    }

    private suspend fun enqueueSuccessfulSaleReport() {
        val context = currentPreparingContext ?: return
        val saleId = context.subscriptionPourContext?.saleId ?: UUID.randomUUID().toString()
        val pendingSale =
            PendingSale(
                saleId = saleId,
                soldAt = TelemetryIsoTimestamps.nowUtc(),
                drinkId = context.tasteId,
                volumeMl = context.volumeMl,
                amountRub = context.saleTotalPriceRub,
                payMethod = resolveSalePayMethod(context.salePayMethod),
                concentrationRatio = context.concentrationRatio,
            )
        runCatching {
            salesSyncCoordinator.enqueueAndTrySend(pendingSale)
        }.onFailure { Timber.tag(TAG).e(it, "enqueue sale.report failed") }
    }

    private suspend fun finalizeSubscriptionPourIfNeeded(dispensedVolumeMl: Int) {
        val ctx = currentPreparingContext?.subscriptionPourContext ?: return
        offlinePourCoordinator.finalizePour(ctx.requestUuid, dispensedVolumeMl)
        waterOutboxStore.enqueueWaterUse(
            clientId = ctx.clientId,
            requestUuid = ctx.requestUuid,
            volumeMl = dispensedVolumeMl,
            drinkId = currentPreparingContext?.tasteId,
            saleId = ctx.saleId,
            isFree = true,
        )
        offlinePourCoordinator.enqueueForSync(ctx.requestUuid, ctx.clientId, isFree = true)
        outboxDrainCoordinator.onEnqueue()
    }

    private fun offlineDenyMessage(reason: OfflineAuthorizationReason): String =
        when (reason) {
            OfflineAuthorizationReason.OFFLINE_NO_GRANT -> "Нет offline-разрешения. Подключите сеть."
            OfflineAuthorizationReason.OFFLINE_GRANT_EXPIRED -> "Offline-разрешение истекло."
            OfflineAuthorizationReason.OFFLINE_GRANT_REVOKED -> "Offline-разрешение отозвано."
            OfflineAuthorizationReason.OFFLINE_GRANT_TAMPERED -> "Offline-разрешение недействительно."
            OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE -> "Ненадёжные часы. Подключите сеть."
            OfflineAuthorizationReason.OFFLINE_POUR_LIMIT -> "Лимит offline-наливов исчерпан."
            OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT -> "Превышен offline-объём."
            OfflineAuthorizationReason.OFFLINE_DAILY_EXCEEDED -> "Превышен дневной лимит."
            OfflineAuthorizationReason.OFFLINE_DISABLED -> "Offline-налив недоступен для тарифа."
            OfflineAuthorizationReason.OFFLINE_FEATURE_DISABLED -> "Offline-налив отключён."
            OfflineAuthorizationReason.GRANTED -> "OK"
            OfflineAuthorizationReason.OFFLINE_GRANT_MACHINE_MISMATCH -> "Grant не для этой машины."
        }

    private fun resolveSalePayMethod(salePayMethod: String?): String =
        salePayMethod?.takeIf { it.isNotBlank() } ?: "FREE"

    private suspend fun persistPreparingTimeRecord() {
        val context = currentPreparingContext ?: return
        val elapsedSec =
            (android.os.SystemClock.elapsedRealtime() - context.startedAtMonotonicMs) / 1000.0
        runCatching {
            preparingTimeHistoryStore.append(
                PreparingTimeRecord(
                    timestampEpochMs = context.startedAtMs,
                    tasteId = context.tasteId,
                    drinkName = context.drinkName,
                    containerNumber = context.containerNumber,
                    volumeMl = context.volumeMl,
                    recipeDrinkVolumeMl = context.recipeDrinkVolumeMl,
                    recipeWaterMl = context.recipeWaterMl,
                    actualWaterMl = context.actualWaterMl,
                    flowRateMlPerSec = context.flowRateMlPerSec,
                    expectedTimeSec = context.expectedTimeSec,
                    actualTimeSec = elapsedSec,
                ),
            )
            waterCalibrationService.applyObservedFlowRate(
                actualWaterMl = context.actualWaterMl,
                actualTimeSec = elapsedSec,
            )
        }.onFailure { Timber.tag(TAG).e(it, "persistPreparingTimeRecord failed") }
    }

    private data class CurrentPreparingContext(
        val startedAtMs: Long,
        val startedAtMonotonicMs: Long,
        val tasteId: Int,
        val drinkName: String,
        val containerNumber: Int,
        val volumeMl: Int,
        val recipeDrinkVolumeMl: Int,
        val recipeWaterMl: Double,
        val actualWaterMl: Double,
        val flowRateMlPerSec: Double,
        val expectedTimeSec: Int,
        val saleTotalPriceRub: Double,
        val salePayMethod: String?,
        val concentrationRatio: Double,
        val subscriptionPourContext: SubscriptionPourContext? = null,
    )

    companion object {
        private const val TAG = "PreparingManager"
    }
}
