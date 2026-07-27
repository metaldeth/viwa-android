package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.mvp.cells.CellSchemaReportCellWire
import com.viwa.android.data.remote.telemetry.mvp.cells.CellVolumeUpdateWire
import com.viwa.android.data.remote.telemetry.mvp.cells.TelemetryCellsMessageCodec
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
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
    private val waterCalibrationService: WaterCalibrationService,
    private val conversionFactorMigration: SyrupConversionFactorMigration,
    private val syrupCalibrationInventory: SyrupCalibrationInventory,
) : MvpTelemetryCellsSyncHandler {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    /** Прогрев Flow/JsonStore до первой подписки (task-08 review M-2). */
    suspend fun warmUp() {
        syrupCalibrationInventory.migrateLegacyConversionFactorsIfNeeded()
        repository.getSnapshot()
    }

    override suspend fun onWebSocketHello() {
        sendSchemaReport()
        // Wait for optional post-schema cells.snapshot so dashboard waterPumpTenths wins
        // before we uplink controller value (avoids overwriting offline PATCH).
        delay(POST_SCHEMA_CALIBRATION_REPORT_DELAY_MS)
        syncControllerFromSnapshotBeforeReport()
        sendMachineCalibrationReport()
    }

    override suspend fun onCellsSnapshot(payloadJson: String) {
        val legacyFactors = conversionFactorMigration.loadLegacyConversionFactors()
        val decoded = codec.decodeSnapshotPayload(payloadJson, legacyConversionFactors = legacyFactors)
        val snapshot = applyRemoteMachineCalibration(decoded)
        repository.replaceSnapshot(snapshot)
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
    suspend fun onLocalVolumeChange(updates: List<CellVolumeUpdateWire>) {
        if (updates.isEmpty()) return
        applyVolumeUpdatesToSnapshot(updates)
        sendVolumeReport(updates)
    }

    /** Локальное изменение inventory/content → обновить snapshot → uplink без denormalized полей. */
    suspend fun onLocalContentChange(cells: List<TelemetryCell>) {
        if (cells.isEmpty()) return
        applyContentUpdatesToSnapshot(cells)
        sendContentReport(cells)
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
            .onSuccess { maybeSendInitialContentReport(snapshot) }
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

    private suspend fun sendVolumeReport(updates: List<CellVolumeUpdateWire>) {
        val payloadJson = codec.encodeVolumeReportPayload(updates)
        sendCellsMessage(type = "cells.volume.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: volume report failed") }
    }

    private suspend fun sendContentReport(cells: List<TelemetryCell>) {
        val payloadJson = codec.encodeContentReportPayload(cells)
        sendCellsMessage(type = "cells.content.report", payloadJson = payloadJson)
            .onFailure { Timber.w(it, "TelemetryCellsSync: content report failed") }
    }

    private suspend fun sendCellsMessage(
        type: String,
        payloadJson: String,
    ): Result<Unit> {
        val payloadObject = json.parseToJsonElement(payloadJson).jsonObject
        return wsManager.sendEnvelope(type = type, payload = payloadObject).map { }
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
    }
}
