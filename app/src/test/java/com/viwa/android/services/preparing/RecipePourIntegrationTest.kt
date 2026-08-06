package com.viwa.android.services.preparing

import com.viwa.android.data.local.recipe.CellEffectiveRecipeStore
import com.viwa.android.data.local.recipe.FakeCellEffectiveRecipeDao
import com.viwa.android.data.remote.telemetry.mvp.MvpHelloCapabilitiesDto
import com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto
import com.viwa.android.data.remote.telemetry.mvp.cells.MvpRecipeSyncCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.remote.telemetry.mvp.cells.RecipeSyncCoordinator
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.domain.inventory.InventoryCellRecipeSupport
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.model.WaterCalibrationData
import com.viwa.android.domain.model.customer.DrinkConcentration
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.recipe.CellEffectiveRecipeSource
import com.viwa.android.domain.recipe.RecipeCanonicalTriple
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.telemetry.DispenseTelemetryFactory
import com.viwa.android.domain.telemetry.PaidCompleteSnapshot
import com.viwa.android.hardware.FlowStripRgbCoordinator
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.ControllerResponseEvent
import com.viwa.android.hardware.controller.ResponseCommand
import com.viwa.android.services.calibration.WaterCalibrationService
import com.viwa.android.services.controller.ViwaControllerStateService
import com.viwa.android.services.drink.ViwaDrinkPreparingService
import com.viwa.android.services.drink.ViwaDrinkSelectionService
import com.viwa.android.services.inventory.InventoryService
import com.viwa.android.services.telemetry.ViwaTelemetryService
import com.viwa.android.domain.offline.OfflinePourTransactionCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AND-6 / AC-30 — PreparingManager pour integration after task-19.
 * Frozen per-pour telemetry integers + fallback/readiness when effective missing or gate off.
 */
class RecipePourIntegrationTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var effectiveStore: CellEffectiveRecipeStore
    private lateinit var recipeSyncCoordinator: RecipeSyncCoordinator
    private lateinit var cellsRepository: TelemetryCellsRepository
    private lateinit var drinkSelection: ViwaDrinkSelectionService
    private lateinit var dispenseSync: TelemetryDispenseSyncCoordinator
    private lateinit var gateway: ControllerGateway
    private lateinit var responseFlow: MutableSharedFlow<ControllerResponseEvent>
    private lateinit var manager: PreparingManager

    private val cellUuid = "cell-pour-int"
    private val tasteId = 4
    private val customizedTriple =
        RecipeCanonicalTriple(
            baseDrinkVolumeMl = 300,
            waterDeciMl = 2600,
            productDeciMl = 400,
        )

    @Before
    fun setUp() {
        PreparingManager.pourFromEffectiveOverrideForTests = null
        effectiveStore =
            CellEffectiveRecipeStore(
                dao = FakeCellEffectiveRecipeDao(),
                featureEnabled = { true },
            )
        effectiveStore.setRuntimeManagedModeActive(true)
        recipeSyncCoordinator = RecipeSyncCoordinator.forTests()
        runBlocking {
            recipeSyncCoordinator.configureFromHello(recipeHello())
            recipeSyncCoordinator.beginInitialSync()
            recipeSyncCoordinator.completeUplinkPhase(success = true)
            recipeSyncCoordinator.markManagedModeReady()
        }

        cellsRepository = mockk()
        coEvery { cellsRepository.getSnapshot() } returns pourSnapshot()

        drinkSelection = mockk(relaxed = true)
        dispenseSync = mockk(relaxed = true)
        gateway = mockk(relaxed = true)
        responseFlow = MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
        every { gateway.incomingResponses } returns responseFlow.asSharedFlow()

        val waterCalibrationService = mockk<WaterCalibrationService>()
        coEvery { waterCalibrationService.loadCalibration() } returns
            WaterCalibrationData(flowRateMlPerSec = 20.0, waterPumpTenths = 100)

        val controllerState = mockk<ViwaControllerStateService>()
        coEvery { controllerState.ensureAutoMode(any()) } returns Unit

        manager =
            PreparingManager(
                drinkSelection = drinkSelection,
                drinkPreparing = mockk<ViwaDrinkPreparingService>(relaxed = true),
                cellsRepository = cellsRepository,
                waterCalibrationService = waterCalibrationService,
                controllerState = controllerState,
                gateway = gateway,
                onStateChanged = {},
                inventoryService = mockk<InventoryService>(relaxed = true),
                preparingTimeHistoryStore = mockk(relaxed = true),
                waterCounter = mockk(relaxed = true),
                flowStripRgbCoordinator = mockk<FlowStripRgbCoordinator>(relaxed = true),
                dispenseSyncCoordinator = dispenseSync,
                offlinePourCoordinator = mockk<OfflinePourTransactionCoordinator>(relaxed = true),
                outboxDrainCoordinator = mockk<MachineOutboxDrainCoordinator>(relaxed = true),
                telemetryService = mockk<ViwaTelemetryService>(relaxed = true),
                effectiveRecipeStore = effectiveStore,
                recipeSyncCoordinator = recipeSyncCoordinator,
                scope = CoroutineScope(testDispatcher),
            )
    }

    @After
    fun tearDown() {
        PreparingManager.pourFromEffectiveOverrideForTests = null
        manager.resetSession()
    }

    @Test
    fun `prepareDrink uses effective integers for chooseDrink and frozen paid telemetry AC-30`() =
        runTest(testDispatcher) {
            PreparingManager.pourFromEffectiveOverrideForTests = true
            persistEffective(customizedTriple)

            val containerSlot = slot<DrinkContainer>()
            val paidSlot = slot<PaidCompleteSnapshot>()
            coEvery {
                drinkSelection.chooseDrink(
                    container = capture(containerSlot),
                    drinkVolumeMl = 700,
                    waterOption = DrinkWaterOption.STANDARD,
                    concentrationRatio = any(),
                    flowRateMlPerSec = any(),
                )
            } returns 12
            coEvery { dispenseSync.enqueuePaidComplete(capture(paidSlot)) } returns Unit

            val result =
                manager.prepareDrink(
                    tasteId = tasteId,
                    volumeMl = 700,
                    saleTotalPriceRub = 150.0,
                    salePayMethod = "CARD",
                )
            responseFlow.emit(ControllerResponseEvent(ResponseCommand.DrinkPreparingSuccess, byteArrayOf()))
            advanceUntilIdle()

            assertEquals(PrepareDrinkResult.Ok(12), result)
            assertTrue(containerSlot.isCaptured)
            assertEquals(700, containerSlot.captured.product.dosage.drinkVolume)

            val expectedScaled =
                InventoryCellRecipeSupport.dosageScaledToPourVolumeOrNull(
                    baseTriple = customizedTriple,
                    targetVolumeMl = 700,
                    conversionFactor = 4.0,
                )
            requireNotNull(expectedScaled)
            assertEquals(expectedScaled.water, containerSlot.captured.product.dosage.water, 0.01)
            assertEquals(expectedScaled.product, containerSlot.captured.product.dosage.product, 0.01)

            assertTrue(paidSlot.isCaptured)
            assertEquals(300, paidSlot.captured.recipeDrinkVolumeMl)
            assertEquals(260.0, paidSlot.captured.recipeWaterMl!!, 0.01)
            assertEquals(40.0, paidSlot.captured.recipeProductMl!!, 0.01)
        }

    @Test
    fun `prepareDrink falls back to legacy when effective missing with readiness gate on`() =
        runTest(testDispatcher) {
            PreparingManager.pourFromEffectiveOverrideForTests = true
            val containerSlot = slot<DrinkContainer>()
            coEvery {
                drinkSelection.chooseDrink(
                    container = capture(containerSlot),
                    drinkVolumeMl = 300,
                    waterOption = any(),
                    concentrationRatio = any(),
                    flowRateMlPerSec = any(),
                )
            } returns 10

            val result = manager.prepareDrink(tasteId = tasteId, volumeMl = 300)
            responseFlow.emit(ControllerResponseEvent(ResponseCommand.DrinkPreparingSuccess, byteArrayOf()))
            advanceUntilIdle()

            assertEquals(PrepareDrinkResult.Ok(10), result)
            assertEquals(270.0, containerSlot.captured.product.dosage.water, 0.01)
            assertEquals(30.0, containerSlot.captured.product.dosage.product, 0.01)
        }

    @Test
    fun `prepareDrink uses legacy template when pour gate override off AND-6 readiness`() =
        runTest(testDispatcher) {
            PreparingManager.pourFromEffectiveOverrideForTests = false
            persistEffective(customizedTriple)
            val containerSlot = slot<DrinkContainer>()
            coEvery {
                drinkSelection.chooseDrink(
                    container = capture(containerSlot),
                    drinkVolumeMl = 300,
                    waterOption = any(),
                    concentrationRatio = any(),
                    flowRateMlPerSec = any(),
                )
            } returns 10

            val result = manager.prepareDrink(tasteId = tasteId, volumeMl = 300)
            responseFlow.emit(ControllerResponseEvent(ResponseCommand.DrinkPreparingSuccess, byteArrayOf()))
            advanceUntilIdle()

            assertEquals(PrepareDrinkResult.Ok(10), result)
            assertEquals(270.0, containerSlot.captured.product.dosage.water, 0.01)
            assertEquals(30.0, containerSlot.captured.product.dosage.product, 0.01)
        }

    @Test
    fun `frozen telemetry dosage matches DispenseTelemetryFactory from effective base`() {
        val baseDosage =
            DrinkDosage(
                conversionFactor = 4.0,
                drinkVolume = 300,
                water = 260.0,
                product = 40.0,
            )
        val paid =
            DispenseTelemetryFactory.paidComplete(
                transactionId = "tx-int",
                requestUuid = "req-int",
                volumeMl = 700,
                amountRub = 150.0,
                payMethod = "CARD",
                productId = "prod-1",
                productNameSnapshot = "Cola",
                concentration = DrinkConcentration.Standard,
                dosage = baseDosage,
            )
        assertEquals(300, paid.recipeDrinkVolumeMl)
        assertEquals(260.0, paid.recipeWaterMl!!, 0.01)
        assertEquals(40.0, paid.recipeProductMl!!, 0.01)
    }

    private suspend fun persistEffective(triple: RecipeCanonicalTriple) {
        effectiveStore.applyLocalEffectiveRecipe(
            cellId = cellUuid,
            triple = triple,
            source = CellEffectiveRecipeSource.COMMAND,
            productId = "prod-pour",
            baseVersionId = "base-v1",
        )
    }

    private fun pourSnapshot(): TelemetryCellsSnapshot =
        TelemetryCellsSnapshot(
            products = listOf(TelemetryProduct(uuid = "prod-pour", name = "Pour Cola", tasteMediaKey = "cherry")),
            cells =
                listOf(
                    TelemetryCell(
                        uuid = cellUuid,
                        cellNumber = tasteId,
                        productUuid = "prod-pour",
                        productName = "Pour Cola",
                        tasteMediaKey = "cherry",
                        volume = 800,
                        maxVolume = 5000,
                        dosage1Price = 12000,
                        dosage2Price = 18000,
                        conversionFactor = 4.0,
                    ),
                ),
        )

    private fun recipeHello(): MvpHelloPayloadDto =
        MvpHelloPayloadDto(
            serialNumber = "VIWA-POUR",
            protocolVersion = 4,
            capabilities = MvpHelloCapabilitiesDto(recipeSync = MvpRecipeSyncCapabilityDto()),
        )
}
