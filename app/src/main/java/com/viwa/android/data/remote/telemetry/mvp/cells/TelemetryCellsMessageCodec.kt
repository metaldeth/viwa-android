package com.viwa.android.data.remote.telemetry.mvp.cells

import com.viwa.android.domain.model.MachineCalibration
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.telemetry.PhysicalCellDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class CellSchemaReportCellWire(
    val uuid: String,
    val cellNumber: Int,
    val maxVolume: Int,
    val blockVolume: Int? = null,
    val sosVolume: Int? = null,
)

@Serializable
internal data class CellSchemaReportPayloadWire(
    val schemaHash: String? = null,
    val clientSchemaHash: String? = null,
    val clientContentRevision: Int? = null,
    val cells: List<CellSchemaReportCellWire>,
)

@Serializable
data class CellVolumeUpdateWire(
    val uuid: String,
    val volume: Int,
    val blockVolume: Int? = null,
    val sosVolume: Int? = null,
)

@Serializable
internal data class CellVolumeReportPayloadWire(
    val updates: List<CellVolumeUpdateWire>,
)

@Serializable
internal data class CellContentReportWire(
    val uuid: String,
    val cellNumber: Int,
    val productUuid: String? = null,
    val blockVolume: Int = 0,
    val sosVolume: Int = 0,
    val volume: Int = 0,
    val maxVolume: Int,
    val dosage1Price: Int? = null,
    val dosage2Price: Int? = null,
    val conversionFactor: Double? = null,
    /** true only for explicit operator taste override in service menu. */
    val operatorOverride: Boolean? = null,
)

@Serializable
internal data class CellContentReportPayloadWire(
    val cells: List<CellContentReportWire>,
)

@Serializable
internal data class ProductWire(
    val uuid: String,
    val name: String,
    val tasteMediaKey: String,
)

@Serializable
internal data class CellFullWire(
    val uuid: String,
    val cellNumber: Int,
    val productUuid: String? = null,
    val productName: String? = null,
    val tasteMediaKey: String? = null,
    val blockVolume: Int = 0,
    val sosVolume: Int = 0,
    val volume: Int = 0,
    val maxVolume: Int,
    val dosage1Price: Int? = null,
    val dosage2Price: Int? = null,
    val conversionFactor: Double? = null,
)

@Serializable
internal data class MachineCalibrationWire(
    val waterPumpTenths: Int,
)

@Serializable
internal data class MachineCalibrationReportPayloadWire(
    val waterPumpTenths: Int,
)

@Serializable
internal data class CellsSnapshotPayloadWire(
    val schemaHash: String? = null,
    val contentRevision: Int? = null,
    val products: List<ProductWire> = emptyList(),
    val cells: List<CellFullWire> = emptyList(),
    val machineCalibration: MachineCalibrationWire? = null,
)

@Singleton
class TelemetryCellsMessageCodec
@Inject
constructor() {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    fun encodeSchemaReportPayload(
        schemaCells: List<CellSchemaReportCellWire>,
        snapshot: TelemetryCellsSnapshot? = null,
        schemaHash: String? = null,
    ): String {
        val payload =
            CellSchemaReportPayloadWire(
                schemaHash = schemaHash,
                clientSchemaHash = snapshot?.schemaHash,
                clientContentRevision = snapshot?.contentRevision,
                cells = schemaCells,
            )
        return json.encodeToString(CellSchemaReportPayloadWire.serializer(), payload)
    }

    fun encodeSchemaReportPayloadFromPhysicalCells(
        physicalCells: List<PhysicalCellDefinition>,
        uuidsByCellNumber: Map<Int, String>,
        snapshot: TelemetryCellsSnapshot? = null,
        schemaHash: String? = null,
    ): String {
        val cells =
            physicalCells.map { definition ->
                CellSchemaReportCellWire(
                    uuid = uuidsByCellNumber.getValue(definition.cellNumber),
                    cellNumber = definition.cellNumber,
                    maxVolume = definition.maxVolume,
                )
            }
        return encodeSchemaReportPayload(schemaCells = cells, snapshot = snapshot, schemaHash = schemaHash)
    }

    fun encodeVolumeReportPayload(updates: List<CellVolumeUpdateWire>): String =
        json.encodeToString(
            CellVolumeReportPayloadWire.serializer(),
            CellVolumeReportPayloadWire(updates = updates),
        )

    fun encodeContentReportPayload(
        cells: List<TelemetryCell>,
        operatorOverride: Boolean = false,
    ): String {
        val wireCells =
            cells.map { cell ->
                CellContentReportWire(
                    uuid = cell.uuid,
                    cellNumber = cell.cellNumber,
                    productUuid = cell.productUuid,
                    blockVolume = cell.blockVolume,
                    sosVolume = cell.sosVolume,
                    volume = cell.volume,
                    maxVolume = cell.maxVolume,
                    dosage1Price = cell.dosage1Price,
                    dosage2Price = cell.dosage2Price,
                    conversionFactor = cell.conversionFactor,
                    operatorOverride = operatorOverride.takeIf { it },
                )
            }
        return json.encodeToString(
            CellContentReportPayloadWire.serializer(),
            CellContentReportPayloadWire(cells = wireCells),
        )
    }

    fun encodeMachineCalibrationReportPayload(waterPumpTenths: Int): String =
        json.encodeToString(
            MachineCalibrationReportPayloadWire.serializer(),
            MachineCalibrationReportPayloadWire(waterPumpTenths = waterPumpTenths),
        )

    fun decodeSnapshotPayload(
        payloadJson: String,
        savedAtEpochMs: Long = System.currentTimeMillis(),
        legacyConversionFactors: Map<Int, Double> = emptyMap(),
    ): TelemetryCellsSnapshot {
        val wire = json.decodeFromString(CellsSnapshotPayloadWire.serializer(), payloadJson)
        return TelemetryCellsSnapshot(
            schemaHash = wire.schemaHash,
            contentRevision = wire.contentRevision,
            products = wire.products.map { it.toDomain() },
            cells = wire.cells.map { it.toDomain(legacyConversionFactors) },
            machineCalibration = wire.machineCalibration?.toDomain(),
            savedAtEpochMs = savedAtEpochMs,
        )
    }

    /** Проверка uplink content report: denormalized поля не должны попадать в JSON. */
    fun contentReportPayloadObject(payloadJson: String): JsonObject =
        json.parseToJsonElement(payloadJson).jsonObject
}

private fun ProductWire.toDomain(): TelemetryProduct =
    TelemetryProduct(
        uuid = uuid,
        name = name,
        tasteMediaKey = tasteMediaKey,
    )

private fun CellFullWire.toDomain(legacyConversionFactors: Map<Int, Double>): TelemetryCell =
    TelemetryCell(
        uuid = uuid,
        cellNumber = cellNumber,
        productUuid = productUuid,
        productName = productName,
        tasteMediaKey = tasteMediaKey,
        blockVolume = blockVolume,
        sosVolume = sosVolume,
        volume = volume,
        maxVolume = maxVolume,
        dosage1Price = dosage1Price,
        dosage2Price = dosage2Price,
        conversionFactor =
            conversionFactor
                ?: legacyConversionFactors[cellNumber]
                ?: TelemetryCell.DEFAULT_CONVERSION_FACTOR,
    )

private fun MachineCalibrationWire.toDomain(): MachineCalibration =
    MachineCalibration(waterPumpTenths = waterPumpTenths)
