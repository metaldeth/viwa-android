package com.viwa.android.ui.screens.customer

import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste
import com.viwa.android.domain.model.customer.DrinkWaterOption
import com.viwa.android.domain.model.customer.FlowWaterPourType
import com.viwa.android.domain.model.customer.WaterPourByTouchPayload
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import com.viwa.android.hardware.controller.RequestCommand
import com.viwa.android.services.telemetry.SubscribeInformationState
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelUnlimitedWaterTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = executor.asCoroutineDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking {
            DrinkListViewModelTestSupport.clearTrackedViewModels(mainDispatcher)
        }
        Dispatchers.resetMain()
        executor.shutdown()
        if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private suspend fun flushMain(times: Int = 16) {
        repeat(times) {
            withContext(mainDispatcher) {}
            yield()
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            flushMain()
        }
        return false
    }

    private fun createVmWithHoldPour(
        telemetry: ViwaTelemetryService,
        holdPour: HoldPourTelemetryCoordinator,
    ): DrinkListViewModel {
        val gateway = mockk<com.viwa.android.hardware.controller.ControllerGateway>(relaxUnitFun = true)
        every { gateway.incomingResponses } returns
            MutableSharedFlow<com.viwa.android.hardware.controller.ControllerResponseEvent>(
                extraBufferCapacity = 16,
            ).asSharedFlow()
        every { gateway.isPhysicalControllerConnected } returns MutableStateFlow(true).asStateFlow()
        val preparing = mockk<com.viwa.android.services.preparing.PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(com.viwa.android.services.preparing.CustomerPreparingPhase.Idle).asStateFlow()
        return DrinkListViewModel(
            DrinkListViewModelTestSupport.vmConfigRepo(),
            DrinkListViewModelTestSupport.cellsRepositoryMock(),
            preparing,
            gateway,
            com.viwa.android.hardware.controller.FlowTemperatureStore(),
            DrinkListViewModelTestSupport.aqsiManagerMock(),
            mockk(relaxed = true),
            telemetry,
            mockk(relaxed = true),
            mockk(relaxed = true),
            DrinkListViewModelTestSupport.sbpRepositoryMock(),
            DrinkListViewModelTestSupport.nanoKassaRepositoryMock(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            holdPour,
        ).also { DrinkListViewModelTestSupport.trackViewModel(it) }
    }

    @Test
    fun setFlowWaterPourType_coercesPremiumWhenSubscriptionInactive() = runBlocking {
        val vm = DrinkListViewModelTestSupport.createViewModel()
        vm.setUiStateForUnitTests(
            DrinkListUiState(
                isSubscriptionActive = false,
                flowWaterPourType = FlowWaterPourType.Filtered,
            ),
        )
        flushMain()

        vm.setFlowWaterPourType(FlowWaterPourType.Cold)
        flushMain()

        assertEquals(FlowWaterPourType.Filtered, vm.state.value.flowWaterPourType)
        assertEquals(DrinkWaterOption.STANDARD, vm.state.value.waterOption)
    }

    @Test
    fun setWater_coercesSparklingToStandardWithoutActiveSubscription() = runBlocking {
        val subscribeInfo =
            MutableStateFlow<SubscribeInformationState?>(
                SubscribeInformationState(
                    isStatusRequest = true,
                    isActiveSubscribe = true,
                    clientId = "client-1",
                    subscribeDateEnd = "2026-12-31T00:00:00.000Z",
                    volumeMl = 500,
                    maxVolumeMl = 2000,
                ),
            )
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
        flushMain(24)

        vm.setWater(DrinkWaterOption.SPARK)
        flushMain()
        assertEquals(FlowWaterPourType.Sparkling, vm.state.value.flowWaterPourType)

        subscribeInfo.value = subscribeInfo.value!!.copy(isActiveSubscribe = false)
        flushMain(24)

        vm.setWater(DrinkWaterOption.SPARK)
        flushMain()
        assertEquals(FlowWaterPourType.Filtered, vm.state.value.flowWaterPourType)
    }

    @Test
    fun setWater_withActiveDrinkAllowsSparkWithoutSubscription() = runBlocking {
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
        val taste = DrinkTaste(1, "Cola", null, null)
        val product =
            DrinkProduct(
                id = 1,
                name = "Coke",
                taste = taste,
                dosage = DrinkDosage(1.0, 300, 1.0, 1.0),
                dPrices = listOf(DrinkPrice(300, 100)),
            )
        vm.setUiStateForUnitTests(
            DrinkListUiState(
                isSubscriptionActive = false,
                activeContainer =
                    DrinkContainer(
                        containerNumber = 3,
                        sodaStatus = null,
                        product = product,
                        productUuid = "test-product-uuid",
                        volumeMl = 1000,
                        minVolumeMl = 0,
                        isActive = true,
                    ),
            ),
        )
        flushMain()

        vm.setWater(DrinkWaterOption.SPARK)
        flushMain()
        assertEquals(DrinkWaterOption.SPARK, vm.state.value.waterOption)
        assertEquals(FlowWaterPourType.Sparkling, vm.state.value.flowWaterPourType)

        vm.setWater(DrinkWaterOption.COLD)
        flushMain()
        assertEquals(DrinkWaterOption.COLD, vm.state.value.waterOption)
    }

    @Test
    fun subscribeInfoUpdate_coercesPremiumTypeOnExpiry() = runBlocking {
        val subscribeInfo =
            MutableStateFlow<SubscribeInformationState?>(
                SubscribeInformationState(
                    isStatusRequest = true,
                    isActiveSubscribe = true,
                    clientId = "client-1",
                    subscribeDateEnd = "2026-01-01T00:00:00.000Z",
                    volumeMl = 500,
                    maxVolumeMl = 2000,
                ),
            )
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
        vm.setUiStateForUnitTests(
            DrinkListUiState(
                flowWaterPourType = FlowWaterPourType.Cold,
                waterOption = DrinkWaterOption.COLD,
            ),
        )
        flushMain(24)

        subscribeInfo.value = subscribeInfo.value!!.copy(isActiveSubscribe = false, volumeMl = 0)
        assertTrue(awaitCondition { vm.state.value.flowWaterPourType == FlowWaterPourType.Filtered })
        assertEquals(DrinkWaterOption.STANDARD, vm.state.value.waterOption)
    }

    @Test
    fun waterPour_doesNotApplyOptimisticSubscriptionDebit() = runBlocking {
        val holdPour = mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true)
        coEvery { holdPour.beginHoldPourSession(any(), any(), any(), any(), any()) } returns "hold-req-1"
        coEvery { holdPour.finalizeHoldPourSession() } returns 120
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        coEvery { telemetry.loadMachineRegistration() } returns
            MachineRegistration(machineId = "machine-1", serialNumber = "SN-1")
        val vm = createVmWithHoldPour(telemetry, holdPour)
        vm.setUiStateForUnitTests(
            DrinkListUiState(
                scannedSubscriptionClientId = "client-1",
                isSubscriptionActive = true,
                subscriptionVolumeMl = 500,
            ),
        )
        flushMain(24)

        vm.waterPourPointerDown()
        assertTrue(awaitCondition { vm.state.value.isWaterPourActive })
        vm.waterPourPointerUp()
        flushMain(24)

        verify(exactly = 0) { telemetry.applyOptimisticSubscriptionPourDeduction(any()) }
    }

    @Test
    fun waterPour_startsAnonymousHoldTelemetryWithoutClientId() = runBlocking {
        val holdPour = mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true)
        val clientIdSlot = slot<String?>()
        coEvery {
            holdPour.beginHoldPourSession(any(), any(), any(), any(), any())
        } answers {
            clientIdSlot.captured = firstArg()
            "anon-hold"
        }
        coEvery { holdPour.finalizeHoldPourSession() } returns 80
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        coEvery { telemetry.loadMachineRegistration() } returns
            MachineRegistration(machineId = "machine-1", serialNumber = "SN-1")
        val vm = createVmWithHoldPour(telemetry, holdPour)
        flushMain(24)

        vm.waterPourPointerDown()
        assertTrue(awaitCondition { vm.state.value.isWaterPourActive })
        vm.waterPourPointerUp()
        flushMain(24)

        coVerify(exactly = 1) { holdPour.beginHoldPourSession(any(), any(), any(), any(), any()) }
        assertNull(clientIdSlot.captured)
    }

    @Test
    fun waterPourStartPayloadForUnitTests_usesCoercedSelBytes() = runBlocking {
        val subscribeInfo =
            MutableStateFlow<SubscribeInformationState?>(
                SubscribeInformationState(
                    isStatusRequest = true,
                    isActiveSubscribe = true,
                    clientId = "client-1",
                    subscribeDateEnd = "2026-12-31T00:00:00.000Z",
                    volumeMl = 500,
                    maxVolumeMl = 2000,
                ),
            )
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
        flushMain(24)

        vm.setUiStateForUnitTests(
            DrinkListUiState(
                isSubscriptionActive = true,
                flowWaterPourType = FlowWaterPourType.Cold,
            ),
        )
        assertArrayEquals(
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Cold, subscriptionActive = true),
            vm.waterPourStartPayloadForUnitTests(),
        )

        subscribeInfo.value = subscribeInfo.value!!.copy(isActiveSubscribe = false)
        vm.setUiStateForUnitTests(
            vm.state.value.copy(
                isSubscriptionActive = false,
                flowWaterPourType = FlowWaterPourType.Sparkling,
                waterOption = DrinkWaterOption.SPARK,
            ),
        )
        flushMain(24)
        assertArrayEquals(
            WaterPourByTouchPayload.startPayload(FlowWaterPourType.Sparkling, subscriptionActive = false),
            vm.waterPourStartPayloadForUnitTests(),
        )
    }

    @Test
    fun subscriptionClearedMidHold_stopsHardwareAndFinalizesOnce() = runBlocking {
        val subscribeInfo =
            MutableStateFlow<SubscribeInformationState?>(
                SubscribeInformationState(
                    isStatusRequest = true,
                    isActiveSubscribe = true,
                    clientId = "client-1",
                    subscribeDateEnd = "2026-12-31T00:00:00.000Z",
                    volumeMl = 500,
                    maxVolumeMl = 2000,
                ),
            )
        val holdPour = mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true)
        coEvery { holdPour.finalizeHoldPourSession() } returns 95
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val gateway = mockk<com.viwa.android.hardware.controller.ControllerGateway>(relaxUnitFun = true)
        every { gateway.incomingResponses } returns
            MutableSharedFlow<com.viwa.android.hardware.controller.ControllerResponseEvent>(
                extraBufferCapacity = 16,
            ).asSharedFlow()
        every { gateway.isPhysicalControllerConnected } returns MutableStateFlow(true).asStateFlow()
        val preparing = mockk<com.viwa.android.services.preparing.PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(com.viwa.android.services.preparing.CustomerPreparingPhase.Idle).asStateFlow()
        val vm =
            DrinkListViewModel(
                DrinkListViewModelTestSupport.vmConfigRepo(),
                DrinkListViewModelTestSupport.cellsRepositoryMock(),
                preparing,
                gateway,
                com.viwa.android.hardware.controller.FlowTemperatureStore(),
                DrinkListViewModelTestSupport.aqsiManagerMock(),
                mockk(relaxed = true),
                telemetry,
                mockk(relaxed = true),
                mockk(relaxed = true),
                DrinkListViewModelTestSupport.sbpRepositoryMock(),
                DrinkListViewModelTestSupport.nanoKassaRepositoryMock(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                holdPour,
            ).also { DrinkListViewModelTestSupport.trackViewModel(it) }
        flushMain(24)
        vm.setUiStateForUnitTests(
            DrinkListUiState(
                scannedSubscriptionClientId = "client-1",
                isSubscriptionActive = true,
                flowWaterPourType = FlowWaterPourType.Cold,
                waterOption = DrinkWaterOption.COLD,
            ),
        )
        vm.markWaterPourActiveForUnitTests(holdRequestUuid = "mid-hold-req")
        flushMain()

        subscribeInfo.value = null
        assertTrue(awaitCondition { !vm.state.value.isWaterPourActive })
        flushMain(24)

        coVerify(exactly = 1) { holdPour.finalizeHoldPourSession() }
        coVerify(exactly = 1) {
            gateway.sendCommand(RequestCommand.WaterPourByTouch, WaterPourByTouchPayload.stopBody)
        }
        assertEquals(FlowWaterPourType.Filtered, vm.state.value.flowWaterPourType)
        assertEquals(DrinkWaterOption.STANDARD, vm.state.value.waterOption)
        assertNull(vm.state.value.scannedSubscriptionClientId)

        vm.waterPourPointerUp()
        flushMain(24)
        coVerify(exactly = 1) { holdPour.finalizeHoldPourSession() }
    }

    @Test
    fun subscriptionExpiredMidHold_stopsHardwareAndFinalizesOnce() = runBlocking {
        val subscribeInfo =
            MutableStateFlow<SubscribeInformationState?>(
                SubscribeInformationState(
                    isStatusRequest = true,
                    isActiveSubscribe = true,
                    clientId = "client-1",
                    subscribeDateEnd = "2026-01-01T00:00:00.000Z",
                    volumeMl = 500,
                    maxVolumeMl = 2000,
                ),
            )
        val holdPour = mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true)
        coEvery { holdPour.finalizeHoldPourSession() } returns 60
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val vm = createVmWithHoldPour(telemetry, holdPour)
        flushMain(24)
        vm.markWaterPourActiveForUnitTests(holdRequestUuid = "expiry-hold-req")
        vm.setUiStateForUnitTests(
            vm.state.value.copy(
                scannedSubscriptionClientId = "client-1",
                isSubscriptionActive = true,
                flowWaterPourType = FlowWaterPourType.Sparkling,
                waterOption = DrinkWaterOption.SPARK,
                isWaterPourActive = true,
            ),
        )

        subscribeInfo.value =
            subscribeInfo.value!!.copy(isActiveSubscribe = false, volumeMl = 0)
        assertTrue(awaitCondition { vm.state.value.flowWaterPourType == FlowWaterPourType.Filtered })
        flushMain(24)

        coVerify(exactly = 1) { holdPour.finalizeHoldPourSession() }
        assertFalse(vm.state.value.isWaterPourActive)
    }

    @Test
    fun loyaltyCardScan_defaultsToColdAndRestoresServerPreference() = runBlocking {
        val cardScans = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val subscribeInfo = MutableStateFlow<SubscribeInformationState?>(null)
        val telemetry = DrinkListViewModelTestSupport.createTestTelemetry()
        every { telemetry.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
        every { telemetry.loyaltyCardClientScans } returns cardScans.asSharedFlow()
        every { telemetry.subscribeInfo } returns subscribeInfo.asStateFlow()
        every { telemetry.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        val vm = DrinkListViewModelTestSupport.createViewModel(telemetryService = telemetry)
        flushMain(24)

        cardScans.emit("client-1")
        flushMain(24)
        assertEquals(DrinkWaterOption.COLD, vm.state.value.waterOption)
        assertEquals(FlowWaterPourType.Cold, vm.state.value.flowWaterPourType)

        subscribeInfo.value =
            SubscribeInformationState(
                isStatusRequest = true,
                isActiveSubscribe = true,
                clientId = "client-1",
                subscribeDateEnd = "2026-12-31T00:00:00.000Z",
                volumeMl = 500,
                maxVolumeMl = 2000,
                lastPlainWaterType = "SPARKLING",
            )
        flushMain(24)
        assertEquals(DrinkWaterOption.SPARK, vm.state.value.waterOption)
        assertEquals(FlowWaterPourType.Sparkling, vm.state.value.flowWaterPourType)
    }
}
