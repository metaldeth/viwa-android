package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.RecipeOutboxStore
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.remote.telemetry.mvp.cells.CellSchemaReportCellWire
import com.viwa.android.data.remote.telemetry.mvp.cells.CellVolumeUpdateWire
import com.viwa.android.data.remote.telemetry.mvp.cells.CellsContentReportAckAwaiter
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_SYNC_REQUEST
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeDownlinkOverflow
import com.viwa.android.data.remote.telemetry.mvp.cells.TelemetryCellsMessageCodec
import com.viwa.android.domain.recipe.RecipeSyncOrchestrator
import com.viwa.android.domain.model.MachineCalibration
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.telemetry.CellUuidAllocator
import com.viwa.android.domain.telemetry.PhysicalCellDefinition
import com.viwa.android.domain.telemetry.PhysicalCellSchemaProvider
import com.viwa.android.services.calibration.SyrupCalibrationInventory
import com.viwa.android.services.calibration.SyrupConversionFactorMigration
import com.viwa.android.services.calibration.WaterCalibrationService
import com.viwa.android.di.AppIoScope
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Оркестрация cells MVP: post-hello schema report, volume/content uplink, snapshot apply (C-3).
 *
 * MVP merge policy: downlink [cells.snapshot] **полностью заменяет** локальный store,
 * в том числе перезаписывает незакоммиченные локальные правки (см. TZ UC-6 A1).
 */
@Singleton
class TelemetryCellsSyncCoordinator
@Inject
constructor(
    private val repository: TelemetryCellsRepository,
    private val codec: TelemetryCellsMessageCodec,
    private val schemaProvider: PhysicalCellSchemaProvider,
    private val uuidAllocator: CellUuidAllocator,
    private val wsManager: MvpTelemetryWebSocketManager,
    private val contentReportAckAwaiter: CellsContentReportAckAwaiter,
    private val waterCalibrationService: WaterCalibrationService,
    private val conversionFactorMigration: SyrupConversionFactorMigration,
    private val syrupCalibrationInventory: SyrupCalibrationInventory,
    private val effectiveRecipeStore: CellEffectiveRecipeStore,
    private val recipeMessageCodec: RecipeMessageCodec,
    private val recipeSyncCoordinator: RecipeSyncCoordinator,
    private val recipeSyncOrchestrator: RecipeSyncOrchestrator,
    private val recipeOutboxStore: RecipeOutboxStore,
    private val outboxDrainCoordinator: MachineOutboxDrainCoordinator,
    @AppIoScope private val appScope: CoroutineScope,
) : MvpTelemetryCellsSyncHandler {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    private val overflowReconnectMutex = Mutex()
    private var overflowHandlingJob: Job? = null
    @Volatile
    private var lastOverflowReconnectAtMs: Long = 0L

    /**
     * Subscribe once: on recipe downlink overflow, reset transient recipe sync state and
     * request a single WS reconnect/resync (debounced — no duplicate connect loops).
     */
    fun startRecipeDownlinkOverflowHandling(
        scope: CoroutineScope,
        requestReconnect: suspend (reason: String) -> Unit,
    ) {
        if (overflowHandlingJob?.isActive == true) return
        overflowHandlingJob =
            scope.launch {
                recipeSyncCoordinator.overflowEvents.collect { overflow ->
                    handleRecipeDownlinkOverflow(overflow, requestReconnect)
                }
            }
    }

    internal suspend fun handleRecipeDownlinkOverflow(
        overflow: RecipeDownlinkOverflow,
        requestReconnect: suspend (reason: String) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val shouldReconnect =
            overflowReconnectMutex.withLock {
                if (now - lastOverflowReconnectAtMs < OVERFLOW_RECONNECT_DEBOUNCE_MS) {
                    false
                } else {
                    lastOverflowReconnectAtMs = now
                    true
                }
            }
        if (!shouldReconnect) {
            Timber.w(
                "TelemetryCellsSync: recipe downlink overflow debounced " +
                    "(buffered=${overflow.bufferedPreFence} event=${overflow.droppedEventType})",
            )
            return
        }
        Timber.e(
            "TelemetryCellsSync: recipe downlink overflow — reset + reconnect " +
                "(buffered=${overflow.bufferedPreFence} event=${overflow.droppedEventType})",
        )
        recipeSyncOrchestrator.onDisconnect()
        recipeSyncCoordinator.resetOnDisconnect()
        requestReconnect("recipe-downlink-overflow")
    }

    /** Прогрев Flow/JsonStore до первой подписки (task-08 review M-2). */
    suspend fun warmUp() {
        syrupCalibrationInventory.migrateLegacyConversionFactorsIfNeeded()
        repository.getSnapshot()
    }

    override suspend fun onWebSocketHello(hello: MvpHelloPayloadDto) {
        recipeSyncCoordinator.configureFromHello(hello)
        sendSchemaReport()
        // Wait for optional post-schema cells.snapshot so dashboard waterPumpTenths wins
        // before we uplink controller value (avoids overwriting offline PATCH).
        delay(POST_SCHEMA_CALIBRATION_REPORT_DELAY_MS)
        syncControllerFromSnapshotBeforeReport()
        sendMachineCalibrationReport()
    }

    override suspend fun onRecipeSyncControl(payloadJson: String) {
        recipeSyncCoordinator.handleSyncControl(payloadJson)
    }

    override suspend fun onRecipeCommand(payloadJson: String) {
        recipeSyncCoordinator.handleCommand(payloadJson)
    }

    override suspend fun onRecipeDisconnect() {
        recipeSyncOrchestrator.onDisconnect()
        recipeSyncCoordinator.resetOnDisconnect()
    }

    override suspend fun onCellsSnapshot(payloadJson: String) {
        val legacyFactors = conversionFactorMigration.loadLegacyConversionFactors()
        val decoded = codec.decodeSnapshotPayload(payloadJson, legacyConversionFactors = legacyFactors)
        val snapshot = applyRemoteMachineCalibration(decoded)
        repository.replaceSnapshot(snapshot)
        // §7.6: managed effective recipe in Room is authoritative — snapshot never overwrites it.
        if (effectiveRecipeStore.isRuntimeManagedModeActive()) {
            Timber.d("TelemetryCellsSync: snapshot applied (content only); managed effective recipe preserved")
        }
        Timber.i(
            "TelemetryCellsSync: snapshot applied revision=${snapshot.contentRevision} " +
                "cells=${snapshot.cells.size} products=${snapshot.products.size} " +
                "waterPumpTenths=${snapshot.machineCalibration?.waterPumpTenths}",
        )
    }

    override suspend fun onSchemaAck(payload: JsonObject) {
        val schemaHash = payload["schemaHash"]?.jsonPrimitive?.content
        if (schemaHash.isNullOrBlank()) return
        mergeRevisionFields(schemaHash = schemaHash)
    }

    /** Локальное изменение volume → обновить snapshot → best-effort uplink (OQ-7). */
    suspend fun onLocalVolumeChange(updates: List<CellVolumeUpdateWire>): Result<Unit> {
        if (updates.isEmpty()) return Result.success(Unit)
        applyVolumeUpdatesToSnapshot(updates)
        return sendVolumeReport(updates)
    }

    /** Локальное изменение inventory/content → обновить snapshot → uplink без denormalized полей. */
    suspend fun onLocalContentChange(
        cells: List<TelemetryCell>,
        operatorOverride: Boolean = false,
    ): Result<Unit> {
        if (cells.isEmpty()) return Result.success(Unit)
        applyContentUpdatesToSnapshot(cells)
        return sendContentReport(cells, operatorOverride = operatorOverride)
    }

    /**
     * Явная смена вкуса оператором (UC-2 A4): send-before-persist + ack `{ ok, applied }`.
     * Локальный snapshot и customer UI обновляются только после подтверждения сервера.
     *
     * Recipe path: `cells.content.report` с `operatorOverride=true` only — **без** embed recipe
     * (architecture §2 R-A8). Server task-08 enqueues durable `ASSIGN_COPY` after content apply;
     * device receives recipe command on downlink fence, not in this uplink.
     */
    suspend fun sendOperatorTasteOverrideAwaitingAck(updatedCell: TelemetryCell): Result<Unit> {
        val messageId = UUID.randomUUID().toString()
        val deferred = contentReportAckAwaiter.register(messageId)
        val payloadJson =
            codec.encodeContentReportPayload(listOf(updatedCell), operatorOverride = true)
        val sendResult =
            sendCellsMessage(
                type = "cells.content.report",
                payloadJson = payloadJson,
                messageId = messageId,
            )
        if (sendResult.isFailure) {
            contentReportAckAwaiter.cancel(messageId)
            return sendResult
        }
        val ackResult =
            withTimeoutOrNull(OPERATOR_CONTENT_ACK_TIMEOUT_MS) {
                deferred.await()
            }
        if (ackResult == null) {
            contentReportAckAwaiter.cancel(messageId)
            return Result.failure(IllegalStateException("Таймаут подтверждения сервера"))
        }
        return ackResult.fold(
            onSuccess = {
                applyContentUpdatesToSnapshot(listOf(updatedCell))
                Result.success(Unit)
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun sendSchemaReport() {
        val snapshot = repository.getSnapshot()
        val physicalCells = schemaProvider.physicalCells()
        val uuids = uuidAllocator.allocateForPhysicalCells(physicalCells, snapshot)
        val schemaCells = buildSchemaReportCells(physicalCells, uuids, snapshot)
        val payloadJson =
            codec.encodeSchemaReportPayload(
                schemaCells = schemaCells,
                snapshot = snapshot,
            )
        sendCellsMessage(type = "cells.schema.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: schema report failed") }
            .onSuccess {
                maybeRunRecipeUplinkPhase()
                maybeSendInitialContentReport(snapshot)
            }
    }

    private suspend fun maybeRunRecipeUplinkPhase() {
        if (!recipeSyncCoordinator.isHelloEligible()) return
        recipeSyncCoordinator.beginInitialSync()
        recipeSyncOrchestrator.startDownlinkProcessing(appScope)
        recipeOutboxStore.recoverPendingTerminalAcks()
        var uplinkSuccess = true
        recipeOutboxStore.enqueueCompleteEffectiveReports(effectiveRecipeStore)
        if (!flushRecipeOutboxPending()) {
            uplinkSuccess = false
        }
        // Always request downlink delivery when uplink outbox is drained — warm persisted
        // effective recipes skip report enqueue (idempotent) but server still needs
        // sync.request to run deliverAfterReport for pending commands.
        if (!recipeOutboxStore.hasUnsentRecipeEntries()) {
            sendRecipeSyncRequest()
                .onFailure {
                    Timber.w(it, "TelemetryCellsSync: recipe sync.request failed")
                    uplinkSuccess = false
                }
        }
        recipeSyncCoordinator.completeUplinkPhase(uplinkSuccess)
        if (uplinkSuccess) {
            recipeSyncOrchestrator.onUplinkFenceOpened()
        }
    }

    /** Drain durable recipe outbox before downlink fence; reports precede acks via DAO ordering. */
    private suspend fun flushRecipeOutboxPending(): Boolean {
        val sessionGen = wsManager.currentSessionGeneration()
        repeat(MAX_RECIPE_DRAIN_PASSES) {
            if (!recipeOutboxStore.hasUnsentRecipeEntries()) return true
            outboxDrainCoordinator.drain("recipe-uplink-phase", sessionGen)
        }
        return !recipeOutboxStore.hasUnsentRecipeEntries()
    }

    private suspend fun sendRecipeSyncRequest(): Result<Unit> {
        val payloadJson = recipeMessageCodec.encodeSyncRequestPayload()
        return sendCellsMessage(type = RECIPE_WS_TYPE_SYNC_REQUEST, payloadJson = payloadJson).map { }
    }

    private suspend fun sendMachineCalibrationReport() {
        syncControllerFromSnapshotBeforeReport()
        val tenths = waterCalibrationService.resolvePumpTenthsForUplink()
        val payloadJson = codec.encodeMachineCalibrationReportPayload(tenths)
        sendCellsMessage(type = "machine.calibration.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: machine calibration report failed") }
            .onSuccess {
                Timber.i("TelemetryCellsSync: machine calibration report sent waterPumpTenths=$tenths")
            }
    }

    /** Apply snapshot calibration to controller before uplink (avoids stale controller clobbering DB). */
    private suspend fun syncControllerFromSnapshotBeforeReport() {
        val snapshot = repository.getSnapshot() ?: return
        val remoteTenths = snapshot.machineCalibration?.waterPumpTenths ?: return
        val clamped = remoteTenths.coerceIn(1, 255)
        val currentTenths =
            waterCalibrationService.readPumpTenths().getOrNull()
                ?: waterCalibrationService.resolvePumpTenthsForUplink()
        if (currentTenths == clamped) return
        waterCalibrationService.writePumpTenths(clamped)
            .onFailure {
                Timber.w(it, "TelemetryCellsSync: failed to sync controller waterPumpTenths=$clamped before report")
            }
            .onSuccess {
                Timber.i("TelemetryCellsSync: synced controller waterPumpTenths=$clamped before calibration report")
            }
    }

    /** Recommended post-schema content report (OQ-9); отсутствие не блокирует flow. */
    internal suspend fun maybeSendInitialContentReport(snapshot: TelemetryCellsSnapshot?) {
        val cells = snapshot?.cells?.filter(::hasReportableContent).orEmpty()
        if (cells.isEmpty()) return
        sendContentReport(cells)
    }

    private suspend fun sendVolumeReport(updates: List<CellVolumeUpdateWire>): Result<Unit> {
        val payloadJson = codec.encodeVolumeReportPayload(updates)
        return sendCellsMessage(type = "cells.volume.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: volume report failed") }
    }

    private suspend fun sendContentReport(
        cells: List<TelemetryCell>,
        operatorOverride: Boolean = false,
    ): Result<Unit> {
        val payloadJson = codec.encodeContentReportPayload(cells, operatorOverride = operatorOverride)
        return sendCellsMessage(type = "cells.content.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: content report failed") }
    }

    private suspend fun sendCellsMessage(
        type: String,
        payloadJson: String,
        messageId: String = UUID.randomUUID().toString(),
    ): Result<Unit> {
        val payloadObject = json.parseToJsonElement(payloadJson).jsonObject
        return wsManager.sendEnvelope(type = type, payload = payloadObject, messageId = messageId).map { }
    }

    private suspend fun applyRemoteMachineCalibration(snapshot: TelemetryCellsSnapshot): TelemetryCellsSnapshot {
        val remoteTenths = snapshot.machineCalibration?.waterPumpTenths ?: return snapshot
        val clamped = remoteTenths.coerceIn(1, 255)
        val currentTenths =
            waterCalibrationService.readPumpTenths().getOrNull()
                ?: waterCalibrationService.resolvePumpTenthsForUplink()
        if (currentTenths == clamped) {
            return snapshot.copy(machineCalibration = MachineCalibration(waterPumpTenths = clamped))
        }
        waterCalibrationService.writePumpTenths(clamped)
            .onFailure {
                Timber.w(it, "TelemetryCellsSync: failed to write waterPumpTenths=$clamped from snapshot")
            }
            .onSuccess {
                Timber.i("TelemetryCellsSync: applied remote waterPumpTenths=$clamped to controller")
            }
        return snapshot.copy(machineCalibration = MachineCalibration(waterPumpTenths = clamped))
    }

    private suspend fun applyVolumeUpdatesToSnapshot(updates: List<CellVolumeUpdateWire>) {
        val current = repository.getSnapshot() ?: return
        val byUuid = updates.associateBy { it.uuid }
        val merged =
            current.cells.map { cell ->
                val update = byUuid[cell.uuid] ?: return@map cell
                cell.copy(
                    volume = update.volume,
                    blockVolume = update.blockVolume ?: cell.blockVolume,
                    sosVolume = update.sosVolume ?: cell.sosVolume,
                )
            }
        repository.replaceSnapshot(current.copy(cells = merged))
    }

    private suspend fun applyContentUpdatesToSnapshot(cells: List<TelemetryCell>) {
        val current = repository.getSnapshot() ?: return
        val byUuid = cells.associateBy { it.uuid }
        val merged =
            current.cells.map { cell ->
                byUuid[cell.uuid]?.let { updated ->
                    cell.copy(
                        productUuid = updated.productUuid,
                        productName = updated.productName,
                        tasteMediaKey = updated.tasteMediaKey,
                        blockVolume = updated.blockVolume,
                        sosVolume = updated.sosVolume,
                        volume = updated.volume,
                        maxVolume = updated.maxVolume,
                        dosage1Price = updated.dosage1Price,
                        dosage2Price = updated.dosage2Price,
                        conversionFactor = updated.conversionFactor,
                    )
                } ?: cell
            }
        repository.replaceSnapshot(current.copy(cells = merged))
    }

    private suspend fun mergeRevisionFields(
        schemaHash: String? = null,
        contentRevision: Int? = null,
    ) {
        val current = repository.getSnapshot() ?: TelemetryCellsSnapshot()
        val updated =
            current.copy(
                schemaHash = schemaHash ?: current.schemaHash,
                contentRevision = contentRevision ?: current.contentRevision,
            )
        if (updated != current) {
            repository.replaceSnapshot(updated)
        }
    }

    private fun buildSchemaReportCells(
        physicalCells: List<PhysicalCellDefinition>,
        uuids: Map<Int, String>,
        snapshot: TelemetryCellsSnapshot?,
    ): List<CellSchemaReportCellWire> =
        physicalCells.map { definition ->
            val existing = snapshot?.cells?.firstOrNull { it.cellNumber == definition.cellNumber }
            CellSchemaReportCellWire(
                uuid = uuids.getValue(definition.cellNumber),
                cellNumber = definition.cellNumber,
                maxVolume = definition.maxVolume,
                blockVolume = existing?.blockVolume?.takeIf { it != 0 },
                sosVolume = existing?.sosVolume?.takeIf { it != 0 },
            )
        }

    private fun hasReportableContent(cell: TelemetryCell): Boolean =
        cell.productUuid != null ||
            cell.volume != 0 ||
            cell.dosage1Price != null ||
            cell.dosage2Price != null ||
            cell.conversionFactor != TelemetryCell.DEFAULT_CONVERSION_FACTOR

    private companion object {
        const val POST_SCHEMA_CALIBRATION_REPORT_DELAY_MS = 3_000L
        const val OPERATOR_CONTENT_ACK_TIMEOUT_MS = 15_000L
        const val MAX_RECIPE_DRAIN_PASSES = 10
        const val OVERFLOW_RECONNECT_DEBOUNCE_MS = 5_000L
    }
}
