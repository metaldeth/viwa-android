package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.RecipeOutboxTestFixtures
import com.viwa.android.data.local.recipe.CellAssignmentBaseStore
import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellAssignmentBaseDao
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.cells.CellVolumeUpdateWire
import com.viwa.android.data.remote.telemetry.mvp.cells.CellsContentReportAckAwaiter
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.TelemetryCellsMessageCodec
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_MAX_REPORT_CELLS
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_REPORT
import com.viwa.android.data.remote.telemetry.mvp.cells.RECIPE_WS_TYPE_SYNC_REQUEST
import com.viwa.android.data.remote.telemetry.mvp.cells.MvpRecipeSyncCapabilityDto
import com.viwa.android.data.local.recipe.CellEffectiveRecipeEntity
import com.viwa.android.domain.recipe.CellEffectiveRecipe
import com.viwa.android.domain.recipe.CellEffectiveRecipeDefaults
import com.viwa.android.domain.recipe.RecipeCanonical
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.data.repository.TelemetryCellsRepositoryImpl
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.domain.telemetry.CellUuidAllocator
import com.viwa.android.domain.telemetry.DefaultPhysicalCellSchemaProvider
import com.viwa.android.services.calibration.SyrupCalibrationInventory
import com.viwa.android.services.calibration.SyrupConversionFactorMigration
import com.viwa.android.services.calibration.WaterCalibrationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import com.viwa.android.domain.recipe.RecipeCommandAckEmitter
import com.viwa.android.domain.recipe.RecipeCommandApplier
import com.viwa.android.domain.recipe.RecipeCommandInbox
import com.viwa.android.domain.recipe.RecipeSyncOrchestrator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryCellsSyncCoordinatorTest {

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    private val codec = TelemetryCellsMessageCodec()
    private val schemaProvider = DefaultPhysicalCellSchemaProvider()
    private val uuidAllocator = CellUuidAllocator()
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var repository: TelemetryCellsRepositoryImpl
    private lateinit var wsManager: MvpTelemetryWebSocketManager
    private lateinit var waterCalibrationService: WaterCalibrationService
    private lateinit var conversionFactorMigration: SyrupConversionFactorMigration
    private lateinit var syrupCalibrationInventory: SyrupCalibrationInventory
    private lateinit var contentReportAckAwaiter: CellsContentReportAckAwaiter
    private lateinit var recipeDao: FakeCellEffectiveRecipeDao
    private lateinit var outboxStack: RecipeOutboxTestFixtures.RecipeOutboxTestStack
    private lateinit var outboxDrainCoordinator: MachineOutboxDrainCoordinator
    private lateinit var effectiveRecipeStore: CellEffectiveRecipeStore
    private lateinit var recipeMessageCodec: RecipeMessageCodec
    private lateinit var recipeSyncCoordinator: RecipeSyncCoordinator
    private lateinit var recipeSyncOrchestrator: RecipeSyncOrchestrator
    private lateinit var testScope: TestScope
    private lateinit var coordinator: TelemetryCellsSyncCoordinator

    private val defaultHello =
        MvpHelloPayloadDto(
            serialNumber = "VIWA-TEST",
            protocolVersion = 3,
        )

    private val recipeHello =
        MvpHelloPayloadDto(
            serialNumber = "VIWA-TEST",
            protocolVersion = 4,
            capabilities =
                MvpHelloCapabilitiesDto(
                    recipeSync = MvpRecipeSyncCapabilityDto(),
                ),
        )

    @Before
    fun setUp() {
        configRepository = FakeConfigRepository()
        repository = TelemetryCellsRepositoryImpl(configRepository)
        wsManager = mockk(relaxed = true)
        waterCalibrationService = mockk(relaxed = true)
        conversionFactorMigration = mockk(relaxed = true)
        syrupCalibrationInventory = mockk(relaxed = true)
        coEvery { conversionFactorMigration.loadLegacyConversionFactors() } returns emptyMap()
        coEvery { waterCalibrationService.resolvePumpTenthsForUplink() } returns 3
        coEvery { waterCalibrationService.readPumpTenths() } returns Result.failure(IllegalStateException("offline"))
        coEvery { waterCalibrationService.writePumpTenths(any()) } returns Result.success(Unit)
        contentReportAckAwaiter = CellsContentReportAckAwaiter()
        recipeMessageCodec = RecipeMessageCodec()
        testScope = TestScope(UnconfinedTestDispatcher())
        recipeDao = FakeCellEffectiveRecipeDao()
        outboxStack = RecipeOutboxTestFixtures.createOutboxStack(recipeDao = recipeDao)
        outboxDrainCoordinator =
            MachineOutboxDrainCoordinator(
                outboxStore = outboxStack.machineOutboxStore,
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                apiClient = mockk(relaxed = true),
                bearerTokenProvider = mockk(relaxed = true),
                recipeOutboxStore = outboxStack.recipeOutboxStore,
                appScope = testScope,
            )
        effectiveRecipeStore =
            CellEffectiveRecipeStore(
                dao = recipeDao,
                featureEnabled = { false },
            )
        recipeSyncCoordinator = RecipeSyncCoordinator.forTests(recipeMessageCodec)
        val applier = RecipeCommandApplier(effectiveRecipeStore)
        recipeSyncOrchestrator =
            RecipeSyncOrchestrator(
                wsCoordinator = recipeSyncCoordinator,
                inbox = outboxStack.inbox(applier, effectiveRecipeStore),
                effectiveRecipeStore = effectiveRecipeStore,
                assignmentBaseStore =
                    CellAssignmentBaseStore.forTests(
                        dao = FakeCellAssignmentBaseDao(),
                    ),
            )
        coordinator =
            TelemetryCellsSyncCoordinator(
                repository = repository,
                codec = codec,
                schemaProvider = schemaProvider,
                uuidAllocator = uuidAllocator,
                wsManager = wsManager,
                contentReportAckAwaiter = contentReportAckAwaiter,
                waterCalibrationService = waterCalibrationService,
                conversionFactorMigration = conversionFactorMigration,
                syrupCalibrationInventory = syrupCalibrationInventory,
                effectiveRecipeStore = effectiveRecipeStore,
                recipeMessageCodec = recipeMessageCodec,
                recipeSyncCoordinator = recipeSyncCoordinator,
                recipeSyncOrchestrator = recipeSyncOrchestrator,
                recipeOutboxStore = outboxStack.recipeOutboxStore,
                outboxDrainCoordinator = outboxDrainCoordinator,
                appScope = testScope,
            )
        coEvery { wsManager.fsmPhase() } returns TelemetryConnectionPhase.Active
        coEvery { wsManager.currentSessionGeneration() } returns 1L
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("test-message-id")
    }

    @Test
    fun `post-hello schema emits structural cells only in payload cells array`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                schemaHash = "saved-hash",
                contentRevision = 10,
                cells =
                    listOf(
                        sampleCell(
                            uuid = "uuid-1",
                            cellNumber = 1,
                            productUuid = "prod-cherry",
                            productName = "Вишня",
                            tasteMediaKey = "cherry",
                        ),
                    ),
            ),
        )
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope("cells.schema.report", capture(payloadSlot), any()) } returns Result.success("test-message-id")
        coEvery { wsManager.sendEnvelope("cells.content.report", any(), any()) } returns Result.success("test-message-id")

        // when
        coordinator.onWebSocketHello(defaultHello)

        // then
        coVerify { wsManager.sendEnvelope("cells.schema.report", any(), any()) }
        coVerify { wsManager.sendEnvelope("machine.calibration.report", any(), any()) }
        val cells = payloadSlot.captured["cells"]!!.jsonArray
        assertEquals(DefaultPhysicalCellSchemaProvider.DEFAULT_CELL_COUNT, cells.size)
        val first = cells.first().jsonObject
        assertTrue(first.containsKey("uuid"))
        assertTrue(first.containsKey("cellNumber"))
        assertTrue(first.containsKey("maxVolume"))
        assertFalse(first.containsKey("productUuid"))
        assertFalse(first.containsKey("productName"))
        assertFalse(first.containsKey("tasteMediaKey"))
        assertFalse(first.containsKey("volume"))
    }

    @Test
    fun `local volume change produces volume report with uuid and volume only in updates`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells =
                    listOf(
                        sampleCell(uuid = "u1", cellNumber = 1, volume = 500),
                        sampleCell(uuid = "u2", cellNumber = 2, volume = 100),
                    ),
            ),
        )
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), any()) } returns Result.success("test-message-id")

        // when
        coordinator.onLocalVolumeChange(
            listOf(
                CellVolumeUpdateWire(uuid = "u1", volume = 900),
            ),
        )

        // then
        assertEquals("cells.volume.report", typeSlot.captured)
        val update = payloadSlot.captured["updates"]!!.jsonArray.single().jsonObject
        assertEquals("u1", update["uuid"]!!.jsonPrimitive.content)
        assertEquals("900", update["volume"]!!.jsonPrimitive.content)
        assertFalse(update.containsKey("productUuid"))
        assertFalse(update.containsKey("productName"))
        val loaded = repository.getSnapshot()!!
        assertEquals(900, loaded.cells.first { it.uuid == "u1" }.volume)
    }

    @Test
    fun `inventory edit produces content report without denormalized fields`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1)),
            ),
        )
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), any()) } returns Result.success("test-message-id")
        val edited =
            sampleCell(
                uuid = "u1",
                cellNumber = 1,
                productUuid = "prod-cherry",
                productName = "Вишня",
                tasteMediaKey = "cherry",
                volume = 1200,
                dosage1Price = 9900,
            )

        // when
        coordinator.onLocalContentChange(listOf(edited))

        // then
        assertEquals("cells.content.report", typeSlot.captured)
        val cellJson = payloadSlot.captured["cells"]!!.jsonArray.single().jsonObject
        assertEquals("prod-cherry", cellJson["productUuid"]!!.jsonPrimitive.content)
        assertFalse(cellJson.containsKey("productName"))
        assertFalse(cellJson.containsKey("tasteMediaKey"))
        assertFalse(cellJson.containsKey("operatorOverride"))
    }

    @Test
    fun `operator taste override persists only after applied ack`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1, productUuid = "old")),
            ),
        )
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope("cells.content.report", any(), capture(messageIdSlot))
        } answers {
            val messageId = messageIdSlot.captured
            contentReportAckAwaiter.completeAck(
                messageId,
                buildJsonObject {
                    put("ok", true)
                    put("applied", 1)
                },
            )
            Result.success(messageId)
        }
        val edited = sampleCell(uuid = "u1", cellNumber = 1, productUuid = "new-prod", dosage1Price = 9900)

        // when
        val result = coordinator.sendOperatorTasteOverrideAwaitingAck(edited)

        // then
        assertTrue(result.isSuccess)
        assertEquals("new-prod", repository.getSnapshot()!!.cells.single().productUuid)
        assertEquals(9900, repository.getSnapshot()!!.cells.single().dosage1Price)
    }

    @Test
    fun `operator taste override does not persist when applied is zero`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1, productUuid = "old")),
            ),
        )
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope("cells.content.report", any(), capture(messageIdSlot))
        } answers {
            val messageId = messageIdSlot.captured
            contentReportAckAwaiter.completeAck(
                messageId,
                buildJsonObject {
                    put("ok", true)
                    put("applied", 0)
                },
            )
            Result.success(messageId)
        }
        val edited = sampleCell(uuid = "u1", cellNumber = 1, productUuid = "new-prod")

        // when
        val result = coordinator.sendOperatorTasteOverrideAwaitingAck(edited)

        // then
        assertTrue(result.isFailure)
        assertEquals("old", repository.getSnapshot()!!.cells.single().productUuid)
    }

    @Test
    fun `operator assign A4 sends content override only without embedded recipe`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1, productUuid = "old-prod")),
            ),
        )
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), capture(messageIdSlot))
        } answers {
            contentReportAckAwaiter.completeAck(
                messageIdSlot.captured,
                buildJsonObject {
                    put("ok", true)
                    put("applied", 1)
                },
            )
            Result.success(messageIdSlot.captured)
        }
        val edited =
            sampleCell(
                uuid = "u1",
                cellNumber = 1,
                productUuid = "new-prod",
                productName = "New taste",
                dosage1Price = 9900,
            )

        // when
        val result = coordinator.sendOperatorTasteOverrideAwaitingAck(edited)

        // then
        assertTrue(result.isSuccess)
        assertEquals("cells.content.report", typeSlot.captured)
        val cellJson = payloadSlot.captured["cells"]!!.jsonArray.single().jsonObject
        assertEquals("true", cellJson["operatorOverride"]!!.jsonPrimitive.content)
        assertFalse(cellJson.containsKey("effectiveRecipe"))
        assertFalse(cellJson.containsKey("recipe"))
        coVerify(exactly = 0) { wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, any(), any()) }
    }

    @Test
    fun `operator taste override does not persist when websocket send fails`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1, productUuid = "old")),
            ),
        )
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns
            Result.failure(IllegalStateException("WebSocket not connected"))
        val edited = sampleCell(uuid = "u1", cellNumber = 1, productUuid = "new-prod")

        // when
        val result = coordinator.sendOperatorTasteOverrideAwaitingAck(edited)

        // then
        assertTrue(result.isFailure)
        assertEquals("old", repository.getSnapshot()!!.cells.single().productUuid)
    }

    @Test
    fun `content change returns failure when websocket send fails but keeps local snapshot`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1)),
            ),
        )
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns
            Result.failure(IllegalStateException("WebSocket not connected"))
        val edited = sampleCell(uuid = "u1", cellNumber = 1, productUuid = "prod")

        // when
        val result = coordinator.onLocalContentChange(listOf(edited))

        // then
        assertTrue(result.isFailure)
        assertEquals("prod", repository.getSnapshot()!!.cells.single().productUuid)
    }

    @Test
    fun `snapshot downlink replaces entire store including products`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                schemaHash = "old-hash",
                contentRevision = 1,
                products = listOf(TelemetryProduct("p-old", "Old", "cherry")),
                cells = listOf(sampleCell(uuid = "old-uuid", cellNumber = 1)),
            ),
        )
        val payloadJson =
            """
            {
              "schemaHash": "new-hash",
              "contentRevision": 99,
              "products": [
                { "uuid": "p-new", "name": "New", "tasteMediaKey": "lemon" }
              ],
              "cells": [
                {
                  "uuid": "new-uuid",
                  "cellNumber": 2,
                  "productUuid": "p-new",
                  "productName": "New",
                  "tasteMediaKey": "lemon",
                  "blockVolume": 0,
                  "sosVolume": 0,
                  "volume": 500,
                  "maxVolume": 5000
                }
              ]
            }
            """.trimIndent()

        // when
        coordinator.onCellsSnapshot(payloadJson)

        // then
        val loaded = repository.getSnapshot()!!
        assertEquals("new-hash", loaded.schemaHash)
        assertEquals(99, loaded.contentRevision)
        assertEquals(listOf("p-new"), loaded.products.map { it.uuid })
        assertEquals(listOf("new-uuid"), loaded.cells.map { it.uuid })
    }

    @Test
    fun `second schema report includes clientSchemaHash and clientContentRevision from saved snapshot`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                schemaHash = "server-hash-v2",
                contentRevision = 41,
                cells = emptyList(),
            ),
        )
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope("cells.schema.report", capture(payloadSlot), any()) } returns Result.success("test-message-id")
        coEvery { wsManager.sendEnvelope("machine.calibration.report", any(), any()) } returns Result.success("test-message-id")

        // when — first reconnect
        coordinator.onWebSocketHello(defaultHello)
        // when — second reconnect
        coordinator.onWebSocketHello(defaultHello)

        // then
        coVerify(exactly = 2) { wsManager.sendEnvelope("cells.schema.report", any(), any()) }
        assertEquals("server-hash-v2", payloadSlot.captured["clientSchemaHash"]!!.jsonPrimitive.content)
        assertEquals("41", payloadSlot.captured["clientContentRevision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hello handler invokes cells sync when coordinator wired`() = runTest {
        // given — MVP-only path: cells sync always wired via SimpleTelemetryCoordinator
        val cellsSync = mockk<TelemetryCellsSyncCoordinator>(relaxed = true)
        val ws =
            createWsManagerForTests(mockk(relaxed = true))
        val telemetryCoordinator =
            SimpleTelemetryCoordinator(
                apiClient = mockk(relaxed = true),
                wsManager = ws,
                cellsSyncCoordinator = cellsSync,
                dispenseSyncCoordinator = mockk(relaxed = true),
                configRepository = FakeConfigRepository(),
                machineSecretStore = mockk(relaxed = true),
                jwtCache = mockk(relaxed = true),
                flowTemperatureStore = FlowTemperatureStore(),
                networkObserver = mockk(relaxed = true),
                offlineEntitlementCoordinator = mockk(relaxed = true),
                technicianKeySessionCoordinator = mockk(relaxed = true),
                networkValidatedSideEffects = mockk(relaxed = true),
                appScope = this,
            )
        telemetryCoordinator.saveTelemetryConfig(TelemetryConfig())

        // when — simulate WS hello callback
        ws.cellsSyncHandler?.onWebSocketHello(defaultHello)

        // then
            coVerify(exactly = 1) { cellsSync.onWebSocketHello(any<MvpHelloPayloadDto>()) }
    }

    @Test
    fun `snapshot during local edit fully replaces pending local state`() = runTest {
        // given — local pending edit (not yet acked by server)
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                schemaHash = "local-hash",
                contentRevision = 5,
                cells =
                    listOf(
                        sampleCell(
                            uuid = "u1",
                            cellNumber = 1,
                            volume = 777,
                            productUuid = "local-prod",
                            productName = "Local",
                            tasteMediaKey = "cherry",
                        ),
                    ),
            ),
        )
        val serverPayload =
            """
            {
              "schemaHash": "server-hash",
              "contentRevision": 6,
              "products": [
                { "uuid": "srv-prod", "name": "Server", "tasteMediaKey": "lime" }
              ],
              "cells": [
                {
                  "uuid": "u1",
                  "cellNumber": 1,
                  "productUuid": "srv-prod",
                  "productName": "Server",
                  "tasteMediaKey": "lime",
                  "blockVolume": 0,
                  "sosVolume": 0,
                  "volume": 100,
                  "maxVolume": 5000
                }
              ]
            }
            """.trimIndent()

        // when
        coordinator.onCellsSnapshot(serverPayload)

        // then — MVP full replace: pending local volume/product overwritten
        val loaded = repository.getSnapshot()!!
        assertEquals("server-hash", loaded.schemaHash)
        assertEquals(6, loaded.contentRevision)
        assertEquals(100, loaded.cells.single().volume)
        assertEquals("srv-prod", loaded.cells.single().productUuid)
        assertNotEquals("local-prod", loaded.cells.single().productUuid)
        assertNotEquals(777, loaded.cells.single().volume)
    }

    @Test
    fun `calibration report syncs controller from snapshot before uplink`() = runTest {
        // given — snapshot has dashboard value 180, controller still at stale 3
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                schemaHash = "hash",
                contentRevision = 2,
                machineCalibration = com.viwa.android.domain.model.MachineCalibration(waterPumpTenths = 180),
            ),
        )
        coEvery { waterCalibrationService.resolvePumpTenthsForUplink() } returns 180
        coEvery { waterCalibrationService.readPumpTenths() } returns Result.success(3)
        val calibrationPayloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery {
            wsManager.sendEnvelope("machine.calibration.report", capture(calibrationPayloadSlot), any())
        } returns Result.success("test-message-id")

        // when
        coordinator.onWebSocketHello(defaultHello)

        // then
        coVerify { waterCalibrationService.writePumpTenths(180) }
        assertEquals("180", calibrationPayloadSlot.captured["waterPumpTenths"]!!.jsonPrimitive.content)
    }

    @Test
    fun `snapshot with machineCalibration writes pump tenths to controller`() = runTest {
        // given
        coEvery { waterCalibrationService.readPumpTenths() } returns Result.success(5)
        val payloadJson =
            """
            {
              "schemaHash": "hash",
              "contentRevision": 1,
              "cells": [],
              "machineCalibration": { "waterPumpTenths": 7 }
            }
            """.trimIndent()

        // when
        coordinator.onCellsSnapshot(payloadJson)

        // then
        coVerify { waterCalibrationService.writePumpTenths(7) }
        assertEquals(7, repository.getSnapshot()?.machineCalibration?.waterPumpTenths)
    }

    @Test
    fun `schema ack persists server schemaHash for next reconnect`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                contentRevision = 3,
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1)),
            ),
        )

        // when
        coordinator.onSchemaAck(
            buildJsonObject {
                put("ok", true)
                put("schemaHash", "ack-hash-from-server")
            },
        )

        // then
        assertEquals("ack-hash-from-server", repository.getSnapshot()?.schemaHash)
    }

    @Test
    fun `content change merges conversionFactor in snapshot`() = runTest {
        // given
        repository.replaceSnapshot(
            TelemetryCellsSnapshot(
                cells = listOf(sampleCell(uuid = "u1", cellNumber = 1, conversionFactor = 4.0)),
            ),
        )
        val edited = sampleCell(uuid = "u1", cellNumber = 1, conversionFactor = 5.5)

        // when
        coordinator.onLocalContentChange(listOf(edited))

        // then
        assertEquals(5.5, repository.getSnapshot()!!.cells.single().conversionFactor, 0.0001)
    }

    @Test
    fun `recipe reconnect sends sync request when no complete effective rows`() = runTest {
        val dao = FakeCellEffectiveRecipeDao()
        val localRecipeSync = RecipeSyncCoordinator.forTests(recipeMessageCodec)
        val recipeCoordinatorWithSync =
            createCoordinator(
                recipeStore =
                    CellEffectiveRecipeStore(
                        dao = dao,
                        featureEnabled = { true },
                    ),
                recipeSync = localRecipeSync,
                recipeDao = dao,
            )
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("mid")

        recipeCoordinatorWithSync.onWebSocketHello(recipeHello)

        coVerify { wsManager.sendEnvelope(RECIPE_WS_TYPE_SYNC_REQUEST, any(), any()) }
        coVerify(exactly = 0) { wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, any(), any()) }
        assertTrue(localRecipeSync.isManagedModeActive())
        assertTrue(localRecipeSync.isUplinkPhaseComplete())
    }

    @Test
    fun `recipe reconnect sends report batches after schema with integer generations`() = runTest {
        val dao = FakeCellEffectiveRecipeDao()
        val recipeStore =
            CellEffectiveRecipeStore(
                dao = dao,
                featureEnabled = { true },
                clock = { 1_000L },
            )
        val recipeCoordinator =
            createCoordinator(
                recipeStore = recipeStore,
                recipeSync = RecipeSyncCoordinator.forTests(recipeMessageCodec),
                recipeDao = dao,
            )
        val complete =
            CellEffectiveRecipe(
                cellId = "cell-1",
                baseDrinkVolumeMl = 300,
                waterDeciMl = 2700,
                productDeciMl = 300,
                fingerprint = CellEffectiveRecipeDefaults.legacyFingerprint,
                source = CellEffectiveRecipeSource.COMMAND,
                productId = "prod-1",
                baseVersionId = "base-1",
                lastAppliedCommandGeneration = 7L,
                cancelThroughGeneration = 5L,
                updatedAtMs = 1_000L,
            )
        dao.upsert(CellEffectiveRecipeEntity.fromDomain(complete))
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery {
            wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, capture(payloadSlot), any())
        } returns Result.success("mid")
        coEvery { wsManager.sendEnvelope("cells.schema.report", any(), any()) } returns Result.success("mid")
        coEvery { wsManager.sendEnvelope("machine.calibration.report", any(), any()) } returns Result.success("mid")

        recipeCoordinator.onWebSocketHello(recipeHello)

        coVerify { wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, any(), any()) }
        val cellJson = payloadSlot.captured["cells"]!!.jsonArray.single().jsonObject
        assertEquals("7", cellJson["lastAppliedCommandGeneration"]!!.jsonPrimitive.content)
        assertEquals("5", cellJson["cancelThroughGeneration"]!!.jsonPrimitive.content)
        assertEquals("0", cellJson["deviceReportRevision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `recipe report splits into batches of 64 cells`() = runTest {
        val dao = FakeCellEffectiveRecipeDao()
        val recipeStore =
            CellEffectiveRecipeStore(
                dao = dao,
                featureEnabled = { true },
            )
        val triple = CellEffectiveRecipeDefaults.legacyTriple
        val fingerprint = RecipeCanonical.fingerprint(triple)
        repeat(RECIPE_WS_MAX_REPORT_CELLS + 1) { index ->
            dao.upsert(
                CellEffectiveRecipeEntity.fromDomain(
                    CellEffectiveRecipe(
                        cellId = "cell-$index",
                        baseDrinkVolumeMl = triple.baseDrinkVolumeMl,
                        waterDeciMl = triple.waterDeciMl,
                        productDeciMl = triple.productDeciMl,
                        fingerprint = fingerprint,
                        source = CellEffectiveRecipeSource.COMMAND,
                        productId = null,
                        baseVersionId = null,
                        lastAppliedCommandGeneration = 0L,
                        cancelThroughGeneration = 0L,
                        updatedAtMs = 0L,
                    ),
                ),
            )
        }
        val recipeCoordinator =
            createCoordinator(
                recipeStore = recipeStore,
                recipeSync = RecipeSyncCoordinator.forTests(recipeMessageCodec),
                recipeDao = dao,
            )
        coEvery { wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, any(), any()) } returns Result.success("mid")
        coEvery { wsManager.sendEnvelope("cells.schema.report", any(), any()) } returns Result.success("mid")
        coEvery { wsManager.sendEnvelope("machine.calibration.report", any(), any()) } returns Result.success("mid")

        recipeCoordinator.onWebSocketHello(recipeHello)

        coVerify(atLeast = RECIPE_WS_MAX_REPORT_CELLS + 1) {
            wsManager.sendEnvelope(RECIPE_WS_TYPE_REPORT, any(), any())
        }
    }

    private fun createRecipeEnabledCoordinator(): TelemetryCellsSyncCoordinator {
        val recipeStore =
            CellEffectiveRecipeStore(
                dao = FakeCellEffectiveRecipeDao(),
                featureEnabled = { true },
            )
        return createCoordinator(recipeStore = recipeStore, recipeSync = recipeSyncCoordinator)
    }

    private fun createCoordinator(
        recipeStore: CellEffectiveRecipeStore = effectiveRecipeStore,
        recipeSync: RecipeSyncCoordinator = recipeSyncCoordinator,
        recipeDao: FakeCellEffectiveRecipeDao = this.recipeDao,
    ): TelemetryCellsSyncCoordinator {
        val outboxStack = RecipeOutboxTestFixtures.createOutboxStack(recipeDao = recipeDao)
        val drainCoordinator =
            MachineOutboxDrainCoordinator(
                outboxStore = outboxStack.machineOutboxStore,
                wsManagerLazy =
                    object : dagger.Lazy<MvpTelemetryWebSocketManager> {
                        override fun get(): MvpTelemetryWebSocketManager = wsManager
                    },
                apiClient = mockk(relaxed = true),
                bearerTokenProvider = mockk(relaxed = true),
                recipeOutboxStore = outboxStack.recipeOutboxStore,
                appScope = testScope,
            )
        val applier = RecipeCommandApplier(recipeStore)
        val orchestrator =
            RecipeSyncOrchestrator(
                wsCoordinator = recipeSync,
                inbox = outboxStack.inbox(applier, recipeStore),
                effectiveRecipeStore = recipeStore,
                assignmentBaseStore = CellAssignmentBaseStore.forTests(FakeCellAssignmentBaseDao()),
            )
        return TelemetryCellsSyncCoordinator(
            repository = repository,
            codec = codec,
            schemaProvider = schemaProvider,
            uuidAllocator = uuidAllocator,
            wsManager = wsManager,
            contentReportAckAwaiter = contentReportAckAwaiter,
            waterCalibrationService = waterCalibrationService,
            conversionFactorMigration = conversionFactorMigration,
            syrupCalibrationInventory = syrupCalibrationInventory,
            effectiveRecipeStore = recipeStore,
            recipeMessageCodec = recipeMessageCodec,
            recipeSyncCoordinator = recipeSync,
            recipeSyncOrchestrator = orchestrator,
            recipeOutboxStore = outboxStack.recipeOutboxStore,
            outboxDrainCoordinator = drainCoordinator,
            appScope = testScope,
        )
    }

    private fun sampleCell(
        uuid: String,
        cellNumber: Int,
        volume: Int = 0,
        productUuid: String? = null,
        productName: String? = null,
        tasteMediaKey: String? = null,
        dosage1Price: Int? = null,
        conversionFactor: Double = TelemetryCell.DEFAULT_CONVERSION_FACTOR,
    ): TelemetryCell =
        TelemetryCell(
            uuid = uuid,
            cellNumber = cellNumber,
            productUuid = productUuid,
            productName = productName,
            tasteMediaKey = tasteMediaKey,
            volume = volume,
            maxVolume = DefaultPhysicalCellSchemaProvider.DEFAULT_MAX_VOLUME_ML,
            dosage1Price = dosage1Price,
            conversionFactor = conversionFactor,
        )

    private class FakeConfigRepository : ConfigRepository {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun set(key: String, value: String) {
            store[key] = value
        }

        override suspend fun delete(key: String) {
            store.remove(key)
        }

        override suspend fun getJson(key: String): String? = store[key]

        override suspend fun setJson(key: String, json: String) {
            store[key] = json
        }
    }
}
