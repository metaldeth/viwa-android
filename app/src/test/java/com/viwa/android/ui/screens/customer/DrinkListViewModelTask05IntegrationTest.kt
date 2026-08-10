package com.viwa.android.ui.screens.customer

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.payment.aqsi.AqsiLastOperationSnapshotHolder
import com.viwa.android.data.payment.aqsi.AqsiRepositoryImpl
import com.viwa.android.data.payment.aqsi.Arcus2TerminalClient
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.AqsiConfig
import com.viwa.android.domain.model.AqsiPaymentResult
import com.viwa.android.domain.model.CardPaymentMethod
import com.viwa.android.domain.model.CardPaymentMockMode
import com.viwa.android.domain.model.CardPaymentMockOutcome
import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.model.SBPSettings
import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.repository.CardPaymentMethodRepository
import com.viwa.android.domain.repository.CardPaymentMockModeRepository
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.usecase.CheckSBPStatusUseCase
import com.viwa.android.domain.usecase.GetSBPLinkUseCase
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.ControllerResponseEvent
import com.viwa.android.hardware.controller.ControllerTrafficEntry
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.hardware.controller.ViwaControllerTrafficLogger
import com.viwa.android.services.payment.CardPaymentEventLogger
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.data.payment.aqsi.UsbPaymentResult
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PrepareDrinkResult
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import com.viwa.android.services.telemetry.SubscribeInformationState
import com.viwa.android.services.telemetry.ViwaTelemetryService
import com.viwa.android.data.payment.aqsi.AqsiDiagnosticOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Task-05 п.12–14: полный [DrinkListViewModel] + однопоточный [kotlinx.coroutines.Dispatchers.Main],
 * чтобы отработали корутины [androidx.lifecycle.viewModelScope] без зависания тест-диспетчера.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelTask05IntegrationTest {

    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: CoroutineDispatcher

    @Before
    fun setup() {
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking {
            DrinkListViewModelTestSupport.clearTrackedViewModels(mainDispatcher)
        }
        Dispatchers.resetMain()
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

 /**
 * Ожидание условия с прокачкой однопоточного `Main`, куда ставит работу [androidx.lifecycle.viewModelScope].
 * Надёжнее фиксированных [kotlinx.coroutines.delay] для CI.
 */
    private suspend fun awaitCondition(
        timeoutMs: Long = 5000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            withContext(mainDispatcher) {}
            yield()
        }
        return false
    }

 /** Несколько итераций Main после достижения условия (отложенные continuation). */
    private suspend fun flushMain(times: Int = 12) {
        repeat(times) {
            withContext(mainDispatcher) {}
            yield()
        }
    }

    private fun createTestTelemetry(): ViwaTelemetryService {
        val mock = mockk<ViwaTelemetryService>(relaxed = true)
        every { mock.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected()).asStateFlow()
        every { mock.subscribeInfo } returns MutableStateFlow(null).asStateFlow()
        every { mock.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.offlineLoyaltyDenyReason } returns
            MutableSharedFlow<OfflineAuthorizationReason>(extraBufferCapacity = 16).asSharedFlow()
        return mock
    }

    private fun vmConfigRepo(): ConfigRepository =
        object : ConfigRepository {
            private val store =
                mutableMapOf(
                    JsonStoreKeys.USE_MOCK_CONTROLLER to "true",
                    JsonStoreKeys.DEV_FREE_MODE to "true",
                )

            override suspend fun get(key: String): String? = store[key]

            override suspend fun set(key: String, value: String) {
                store[key] = value
            }

            override suspend fun delete(key: String) {
                store.remove(key)
            }

            override suspend fun getJson(key: String): String? = store[key]

            override suspend fun setJson(key: String, jsonStr: String) {
                store[key] = jsonStr
            }
        }

    private fun defaultSbpRepo(): SBPRepository = DrinkListViewModelTestSupport.sbpRepositoryMock()

    private fun createGatewayAndPaymentMocks(): Pair<ControllerGateway, ControllerSbpNotifyService> {
        val gateway = mockk<ControllerGateway>(relaxUnitFun = true)
        val responses = MutableSharedFlow<ControllerResponseEvent>(extraBufferCapacity = 16)
        every { gateway.incomingResponses } returns responses.asSharedFlow()
        every { gateway.isPhysicalControllerConnected } returns MutableStateFlow(true).asStateFlow()
        val sbp = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
        return gateway to sbp
    }

    private fun mockAqsiManager(): AqsiUsbPaymentManager {
        val m = mockk<AqsiUsbPaymentManager>(relaxed = true)
        every { m.exchangeLogFlow } returns MutableStateFlow<List<String>>(emptyList()).asStateFlow()
        every { m.terminalStatusFlow } returns MutableStateFlow("").asStateFlow()
        return m
    }

    private fun createViewModel(
        cardPaymentOrchestrator: CardPaymentOrchestrator,
        preparingManager: PreparingManager,
        telemetryService: ViwaTelemetryService,
        getSBPLinkUseCase: GetSBPLinkUseCase,
        sbpRepository: SBPRepository = defaultSbpRepo(),
    ): DrinkListViewModel {
        val (gw, sbpNotify) = createGatewayAndPaymentMocks()
        val aqsi = mockAqsiManager()
        val cellsRepo = mockk<TelemetryCellsRepository>(relaxUnitFun = true)
        every { cellsRepo.snapshotFlow } returns MutableStateFlow(null).asStateFlow()
        val checkSbp = mockk<CheckSBPStatusUseCase>(relaxed = true)
        coEvery { checkSbp(any()) } returns Result.success(SBPStatus.Pending)
        val nano = DrinkListViewModelTestSupport.nanoKassaRepositoryMock()
        val networkTraffic = mockk<NetworkTrafficLogger>(relaxUnitFun = true)
        every { networkTraffic.entries } returns MutableStateFlow<List<NetworkTrafficEntry>>(emptyList()).asStateFlow()
        val controllerTraffic = mockk<ViwaControllerTrafficLogger>(relaxUnitFun = true)
        every { controllerTraffic.entries } returns
            MutableStateFlow<List<ControllerTrafficEntry>>(emptyList()).asStateFlow()
        val vm =
            DrinkListViewModel(
            vmConfigRepo(),
            cellsRepo,
            preparingManager,
            gw,
            FlowTemperatureStore(),
            aqsi,
            sbpNotify,
            telemetryService,
            getSBPLinkUseCase,
            checkSbp,
            sbpRepository,
            nano,
            networkTraffic,
            controllerTraffic,
            cardPaymentOrchestrator,
            mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true),
        )
        DrinkListViewModelTestSupport.trackViewModel(vm)
        return vm
    }

    private fun sampleContainer(): DrinkContainer {
        val taste = DrinkTaste(1, "Cola", null, null)
        val product =
            DrinkProduct(
                id = 1,
                name = "Coke",
                taste = taste,
                dosage = DrinkDosage(1.0, 300, 1.0, 1.0),
                dPrices =
                    listOf(
                        DrinkPrice(300, 100),
                        DrinkPrice(700, 150),
                    ),
            )
        return DrinkContainer(
            containerNumber = 3,
            sodaStatus = null,
            product = product,
            productUuid = "test-product-uuid",
            volumeMl = 1000,
            minVolumeMl = 0,
            isActive = true,
        )
    }

    private class FakeCardMethodRepo(
        var selected: CardPaymentMethod,
    ) : CardPaymentMethodRepository {
        override suspend fun getSelected(): CardPaymentMethod = selected

        override suspend fun setSelected(method: CardPaymentMethod) {
            selected = method
        }
    }

    private class FakeMockModeRepo(
        private var mode: CardPaymentMockMode = CardPaymentMockMode.Disabled,
    ) : CardPaymentMockModeRepository {
        override suspend fun getMode(): CardPaymentMockMode = mode

        override suspend fun setMode(mode: CardPaymentMockMode) {
            this.mode = mode
        }

        override suspend fun getOutcome(): CardPaymentMockOutcome = CardPaymentMockOutcome.Approved

        override suspend fun setOutcome(outcome: CardPaymentMockOutcome) = Unit
    }

    private class Task05RecordingArcus(
        var payResult: Result<AqsiPaymentResult> = Result.success(AqsiPaymentResult.Approved),
    ) : Arcus2TerminalClient {
        override fun interruptCurrentTcpSession() {}

        override fun testTcpChannel(host: String, port: Int, timeoutMs: Long): Result<Unit> =
            Result.success(Unit)

        override fun initiatePurchase(
            host: String,
            port: Int,
            timeoutMs: Long,
            amountKopecks: Int,
        ): Result<AqsiPaymentResult> = payResult

        override fun cancelPurchase(host: String, port: Int, timeoutMs: Long): Result<Unit> =
            Result.success(Unit)
    }

    private fun aqsiTestConfig(host: String = "192.168.1.10"): ConfigRepository =
        object : ConfigRepository {
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            private val store =
                mutableMapOf(
                    JsonStoreKeys.AQSI_SETTINGS to
                        json.encodeToString(
                            AqsiConfig.serializer(),
                            AqsiConfig(host = host, port = 16107, timeoutMs = 15000L),
                        ),
                )

            override suspend fun get(key: String): String? = store[key]

            override suspend fun set(key: String, value: String) {
                store[key] = value
            }

            override suspend fun delete(key: String) {
                store.remove(key)
            }

            override suspend fun getJson(key: String): String? = store[key]

            override suspend fun setJson(key: String, jsonStr: String) {
                store[key] = jsonStr
            }
        }

    private fun createDrinkVmWithAqsiOrchestrator(
        holder: AqsiLastOperationSnapshotHolder,
        payResult: Result<AqsiPaymentResult>,
        telemetryService: ViwaTelemetryService = createTestTelemetry(),
        preparingOverride: PreparingManager? = null,
    ): DrinkListViewModel {
        val usb = mockAqsiManager()
        coEvery { usb.pay(any()) } answers {
            val amount = arg<Int>(0)
            when (val r = payResult.getOrNull()) {
                AqsiPaymentResult.Approved -> UsbPaymentResult.Success("t", amount)
                is AqsiPaymentResult.Declined ->
                    UsbPaymentResult.Failure("AQSI_DECLINED_${r.publicCode}", r.publicCode)
                AqsiPaymentResult.Cancelled -> UsbPaymentResult.Cancelled
                is AqsiPaymentResult.Error -> UsbPaymentResult.Failure("ERR", r.safeMessage)
                null -> throw payResult.exceptionOrNull() ?: IllegalStateException("pay_failed")
            }
        }
        val aqsiRepo =
            AqsiRepositoryImpl(
                configRepository = aqsiTestConfig(),
                usbPaymentManager = usb,
                lastOperationSnapshotHolder = holder,
                paymentEventLogger = CardPaymentEventLogger(),
                ioDispatcher = Dispatchers.Unconfined,
            )
        val (gw, sbpNotify) = createGatewayAndPaymentMocks()
        coEvery { gw.simulateResponseForTests(any(), any()) } returns Unit
        val orch =
            CardPaymentOrchestrator(
                aqsiRepository = aqsiRepo,
                cardPaymentMockModeRepository = FakeMockModeRepo(),
                paymentEventLogger = CardPaymentEventLogger(),
            )
        val preparing =
            preparingOverride
                ?: mockk<PreparingManager>(relaxed = true).also {
                    every { it.customerPhase } returns
                        MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
                }
        val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
        val cellsRepo = mockk<TelemetryCellsRepository>(relaxUnitFun = true)
        every { cellsRepo.snapshotFlow } returns MutableStateFlow(null).asStateFlow()
        val checkSbp = mockk<CheckSBPStatusUseCase>(relaxUnitFun = true)
        coEvery { checkSbp(any()) } returns Result.success(SBPStatus.Pending)
        val nano = DrinkListViewModelTestSupport.nanoKassaRepositoryMock()
        val networkTraffic = mockk<NetworkTrafficLogger>(relaxUnitFun = true)
        every { networkTraffic.entries } returns
            MutableStateFlow<List<NetworkTrafficEntry>>(emptyList()).asStateFlow()
        val controllerTraffic = mockk<ViwaControllerTrafficLogger>(relaxUnitFun = true)
        every { controllerTraffic.entries } returns
            MutableStateFlow<List<ControllerTrafficEntry>>(emptyList()).asStateFlow()
        val vm =
            DrinkListViewModel(
            vmConfigRepo(),
            cellsRepo,
            preparing,
            gw,
            FlowTemperatureStore(),
            usb,
            sbpNotify,
            telemetryService,
            getSbp,
            checkSbp,
            defaultSbpRepo(),
            nano,
            networkTraffic,
            controllerTraffic,
            orch,
            mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true),
        )
        DrinkListViewModelTestSupport.trackViewModel(vm)
        return vm
    }

    @Test
    fun task05_13_drinkCardDecline_doesNotCallPrepareDrink() =
        runBlocking {
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Failed("declined")
            val tel = createTestTelemetry()
            val preparing = mockk<PreparingManager>(relaxed = true)
            every { preparing.customerPhase } returns
                MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
            val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
            val vm = createViewModel(orch, preparing, tel, getSbp)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(
                awaitCondition {
                    val s = vm.state.value
                    s.paymentError != null && !s.isProcessingPay
                },
            )
            flushMain()
            coVerify(exactly = 0) {
                preparing.prepareDrink(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun task05_14_aqsiApprovedFromOrder_updatesDiagnosticHolder() =
        runBlocking {
            val holder = AqsiLastOperationSnapshotHolder()
            val vm =
                createDrinkVmWithAqsiOrchestrator(
                    holder,
                    payResult = Result.success(AqsiPaymentResult.Approved),
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition { holder.getSnapshot()?.outcome == AqsiDiagnosticOutcome.APPROVED })
            flushMain()
            assertEquals(AqsiDiagnosticOutcome.APPROVED, holder.getSnapshot()?.outcome)
        }

    @Test
    fun task05_14_aqsiDeclineFromOrder_updatesDiagnosticHolder() =
        runBlocking {
            val holder = AqsiLastOperationSnapshotHolder()
            val vm =
                createDrinkVmWithAqsiOrchestrator(
                    holder,
                    payResult = Result.success(AqsiPaymentResult.Declined(publicCode = "051")),
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition { holder.getSnapshot()?.outcome == AqsiDiagnosticOutcome.DECLINED })
            flushMain()
            assertEquals(AqsiDiagnosticOutcome.DECLINED, holder.getSnapshot()?.outcome)
        }

    @Test
    fun task05_14_aqsiErrorFromOrder_updatesDiagnosticHolder() =
        runBlocking {
            val holder = AqsiLastOperationSnapshotHolder()
            val vm =
                createDrinkVmWithAqsiOrchestrator(
                    holder,
                    payResult = Result.success(AqsiPaymentResult.Error(safeMessage = "timeout")),
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition { holder.getSnapshot()?.outcome == AqsiDiagnosticOutcome.ERROR })
            flushMain()
            assertEquals(AqsiDiagnosticOutcome.ERROR, holder.getSnapshot()?.outcome)
        }

    @Test
    fun task05_14_aqsiCancelledFromOrder_updatesDiagnosticHolder() =
        runBlocking {
            val holder = AqsiLastOperationSnapshotHolder()
            val vm =
                createDrinkVmWithAqsiOrchestrator(
                    holder,
                    payResult = Result.success(AqsiPaymentResult.Cancelled),
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition { holder.getSnapshot()?.outcome == AqsiDiagnosticOutcome.CANCELLED })
            flushMain()
            assertEquals(AqsiDiagnosticOutcome.CANCELLED, holder.getSnapshot()?.outcome)
        }

    @Test
    fun task07_aqsiApprovedDrink_prepareDrinkUsesCardSaleMethodLikePax() =
        runBlocking {
            val tel = createTestTelemetry()
            val preparing = mockk<PreparingManager>(relaxed = true)
            every { preparing.customerPhase } returns
                MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
            coEvery {
                preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    null,
                )
            } returns
                PrepareDrinkResult.Ok(estSeconds = 30)

            val holder = AqsiLastOperationSnapshotHolder()
            val vm =
                createDrinkVmWithAqsiOrchestrator(
                    holder,
                    payResult = Result.success(AqsiPaymentResult.Approved),
                    telemetryService = tel,
                    preparingOverride = preparing,
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                ),
            )
            vm.startCardPayment { _, _, _, _, _, _ -> }
            assertTrue(
                awaitCondition {
                    vm.state.value.activeContainer == null && vm.state.value.selectedVolumeMl == null
                },
            )
            flushMain()
            coVerify(exactly = 1) {
                preparing.prepareDrink(
                    tasteId = 1,
                    volumeMl = 300,
                    waterOption = any(),
                    concentrationRatio = any(),
                    saleTotalPriceRub = 100.0,
                    salePayMethod = "CARD",
                )
            }
        }

    @Test
    fun subscriptionFixedVolumePour_navigatesWithSubscribePayMethod() =
        runBlocking {
            val tel = mockk<ViwaTelemetryService>(relaxUnitFun = true)
            every { tel.connectionState } returns
                MutableStateFlow<ConnectionState>(ConnectionState.Connected).asStateFlow()
            every { tel.subscribeInfo } returns
                MutableStateFlow(
                    SubscribeInformationState(
                        isStatusRequest = true,
                        isActiveSubscribe = true,
                        clientId = "client-uuid",
                        subscribeDateEnd = "2026-08-31T00:00:00.000Z",
                        volumeMl = 500,
                        maxVolumeMl = 2_000,
                    ),
                ).asStateFlow()
            every { tel.loyaltyCardClientScans } returns
                MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
            every { tel.invalidLoyaltyCardScans } returns
                MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
            every { tel.offlineLoyaltyDenyReason } returns
                MutableSharedFlow<OfflineAuthorizationReason>(extraBufferCapacity = 16).asSharedFlow()
            coEvery { tel.loadMachineRegistration() } returns
                MachineRegistration(machineId = "machine-1", serialNumber = "SN-1")
            val preparing = mockk<PreparingManager>(relaxed = true)
            every { preparing.customerPhase } returns
                MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
            coEvery {
                preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns PrepareDrinkResult.Ok(estSeconds = 30)
            coEvery { preparing.validateDrinkPreparation(any()) } returns null
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
            val vm = createViewModel(orch, preparing, tel, getSbp)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    scannedSubscriptionClientId = "client-uuid",
                    subscriptionVolumeMl = 500,
                    subscriptionMaxVolumeMl = 2000,
                ),
            )
            var capturedPayMethod: String? = null
            vm.primaryAction { _, _, _, _, payMethod, _ ->
                capturedPayMethod = payMethod
            }
            assertTrue(
                awaitCondition {
                    vm.state.value.activeContainer == null && vm.state.value.selectedVolumeMl == null
                },
            )
            flushMain()
            assertEquals("subscribe", capturedPayMethod)
            coVerify(exactly = 1) {
                preparing.prepareDrink(
                    tasteId = any(),
                    volumeMl = 300,
                    waterOption = any(),
                    concentrationRatio = any(),
                    concentration = any(),
                    saleTotalPriceRub = 0.0,
                    salePayMethod = "SUBSCRIBE",
                    subscriptionPourContext = match { it?.clientId == "client-uuid" },
                )
            }
            coVerify(exactly = 0) { tel.clearSubscribeUiState() }
        }
}
