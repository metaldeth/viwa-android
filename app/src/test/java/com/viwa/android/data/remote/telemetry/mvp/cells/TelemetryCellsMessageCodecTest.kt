package com.viwa.android.data.remote.telemetry.mvp.cells

import com.viwa.android.domain.model.MachineCalibration
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.telemetry.DefaultPhysicalCellSchemaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCellsMessageCodecTest {

    private val codec = TelemetryCellsMessageCodec()

    @Test
    fun encodeContentReport_shouldSerializeProductUuidOnlyWithoutDenormalizedFields() {
        val cell =
            TelemetryCell(
                uuid = "550e8400-e29b-41d4-a716-446655440001",
                cellNumber = 1,
                productUuid = "prod-cherry",
                productName = "Вишня",
                tasteMediaKey = "cherry",
                blockVolume = 0,
                sosVolume = 100,
                volume = 1200,
                maxVolume = 5000,
                dosage1Price = 9900,
                dosage2Price = 14900,
                conversionFactor = 5.5,
            )

        val payloadJson = codec.encodeContentReportPayload(listOf(cell))
        val root = codec.contentReportPayloadObject(payloadJson)
        val firstCell = root["cells"]!!.toString()

        assertTrue(firstCell.contains("\"productUuid\":\"prod-cherry\""))
        assertTrue(firstCell.contains("\"conversionFactor\":5.5"))
        assertFalse(firstCell.contains("productName"))
        assertFalse(firstCell.contains("tasteMediaKey"))
    }

    @Test
    fun encodeContentReport_alwaysIncludesConversionFactor() {
        val cell =
            TelemetryCell(
                uuid = "u1",
                cellNumber = 1,
                maxVolume = 5000,
                conversionFactor = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
            )

        val payloadJson = codec.encodeContentReportPayload(listOf(cell))
        val firstCell = codec.contentReportPayloadObject(payloadJson)["cells"]!!.toString()

        assertTrue(firstCell.contains("\"conversionFactor\":${TelemetryCell.DEFAULT_CONVERSION_FACTOR}"))
    }

    @Test
    fun decodeSnapshotPayload_shouldDeserializeProductsAndDenormalizedCells() {
        val payloadJson =
            """
            {
              "schemaHash": "sha256-hex",
              "contentRevision": 42,
              "products": [
                { "uuid": "prod-cherry", "name": "Вишня", "tasteMediaKey": "cherry" }
              ],
              "cells": [
                {
                  "uuid": "550e8400-e29b-41d4-a716-446655440001",
                  "cellNumber": 1,
                  "productUuid": "prod-cherry",
                  "productName": "Вишня",
                  "tasteMediaKey": "cherry",
                  "blockVolume": 0,
                  "sosVolume": 100,
                  "volume": 1200,
                  "maxVolume": 5000,
                  "dosage1Price": 9900,
                  "dosage2Price": 14900,
                  "conversionFactor": 6.25
                }
              ],
              "machineCalibration": { "waterPumpTenths": 4 }
            }
            """.trimIndent()

        val snapshot = codec.decodeSnapshotPayload(payloadJson, savedAtEpochMs = 1_700_000_000_000L)

        assertEquals("sha256-hex", snapshot.schemaHash)
        assertEquals(42, snapshot.contentRevision)
        assertEquals(
            listOf(TelemetryProduct("prod-cherry", "Вишня", "cherry")),
            snapshot.products,
        )
        assertEquals(
            TelemetryCell(
                uuid = "550e8400-e29b-41d4-a716-446655440001",
                cellNumber = 1,
                productUuid = "prod-cherry",
                productName = "Вишня",
                tasteMediaKey = "cherry",
                blockVolume = 0,
                sosVolume = 100,
                volume = 1200,
                maxVolume = 5000,
                dosage1Price = 9900,
                dosage2Price = 14900,
                conversionFactor = 6.25,
            ),
            snapshot.cells.single(),
        )
        assertEquals(MachineCalibration(waterPumpTenths = 4), snapshot.machineCalibration)
    }

    @Test
    fun decodeSnapshotPayload_missingConversionFactor_usesLegacyMapThenDefault() {
        val payloadJson =
            """
            {
              "cells": [
                {
                  "uuid": "u1",
                  "cellNumber": 1,
                  "maxVolume": 5000
                },
                {
                  "uuid": "u2",
                  "cellNumber": 2,
                  "maxVolume": 5000,
                  "conversionFactor": 3.5
                }
              ]
            }
            """.trimIndent()

        val snapshot =
            codec.decodeSnapshotPayload(
                payloadJson,
                legacyConversionFactors = mapOf(1 to 7.7),
            )

        assertEquals(7.7, snapshot.cells.first { it.cellNumber == 1 }.conversionFactor, 0.0001)
        assertEquals(3.5, snapshot.cells.first { it.cellNumber == 2 }.conversionFactor, 0.0001)
    }

    @Test
    fun decodeSnapshotPayload_missingConversionFactorWithoutLegacy_defaultsToFour() {
        val payloadJson =
            """
            {
              "cells": [
                { "uuid": "u1", "cellNumber": 1, "maxVolume": 5000 }
              ]
            }
            """.trimIndent()

        val snapshot = codec.decodeSnapshotPayload(payloadJson)

        assertEquals(TelemetryCell.DEFAULT_CONVERSION_FACTOR, snapshot.cells.single().conversionFactor, 0.0001)
    }

    @Test
    fun encodeSchemaReportPayload_whenSnapshotExists_includesClientRevisionFields() {
        val snapshot =
            TelemetryCellsSnapshot(
                schemaHash = "local-hash",
                contentRevision = 41,
                products = emptyList(),
                cells = emptyList(),
            )
        val provider = DefaultPhysicalCellSchemaProvider()
        val uuids = provider.physicalCells().associate { it.cellNumber to "uuid-${it.cellNumber}" }

        val payloadJson =
            codec.encodeSchemaReportPayloadFromPhysicalCells(
                physicalCells = provider.physicalCells(),
                uuidsByCellNumber = uuids,
                snapshot = snapshot,
            )

        assertTrue(payloadJson.contains("\"clientSchemaHash\":\"local-hash\""))
        assertTrue(payloadJson.contains("\"clientContentRevision\":41"))
    }

    @Test
    fun encodeVolumeReportPayload_serializesUpdates() {
        val payloadJson =
            codec.encodeVolumeReportPayload(
                listOf(
                    CellVolumeUpdateWire(
                        uuid = "u1",
                        volume = 900,
                        blockVolume = 0,
                        sosVolume = 100,
                    ),
                ),
            )

        assertTrue(payloadJson.contains("\"updates\""))
        assertTrue(payloadJson.contains("\"uuid\":\"u1\""))
        assertTrue(payloadJson.contains("\"volume\":900"))
    }

    @Test
    fun encodeMachineCalibrationReportPayload_serializesWaterPumpTenths() {
        val payloadJson = codec.encodeMachineCalibrationReportPayload(waterPumpTenths = 5)

        assertTrue(payloadJson.contains("\"waterPumpTenths\":5"))
    }

    @Test
    fun conversionFactor_roundTripThroughContentReport() {
        val original =
            TelemetryCell(
                uuid = "u-round",
                cellNumber = 3,
                productUuid = "prod",
                maxVolume = 5000,
                conversionFactor = 8.125,
            )

        val payloadJson = codec.encodeContentReportPayload(listOf(original))
        val cellJson = codec.contentReportPayloadObject(payloadJson)["cells"]!!.toString()
        assertTrue(cellJson.contains("\"conversionFactor\":8.125"))
    }

    @Test
    fun encodeContentReport_operatorOverride_omittedByDefault() {
        val cell = sampleContentCell()

        val payloadJson = codec.encodeContentReportPayload(listOf(cell))
        val cellJson = codec.contentReportPayloadObject(payloadJson)["cells"]!!.toString()

        assertFalse(cellJson.contains("operatorOverride"))
    }

    @Test
    fun encodeContentReport_operatorOverride_trueWhenExplicitServiceAction() {
        val cell = sampleContentCell()

        val payloadJson = codec.encodeContentReportPayload(listOf(cell), operatorOverride = true)
        val cellJson = codec.contentReportPayloadObject(payloadJson)["cells"]!!.toString()

        assertTrue(cellJson.contains("\"operatorOverride\":true"))
    }

    private fun sampleContentCell(): TelemetryCell =
        TelemetryCell(
            uuid = "u1",
            cellNumber = 1,
            productUuid = "prod-cherry",
            maxVolume = 5000,
            dosage1Price = 9900,
            dosage2Price = 14900,
            conversionFactor = 4.0,
        )
}
