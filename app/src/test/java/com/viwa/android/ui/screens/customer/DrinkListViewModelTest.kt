package com.viwa.android.ui.screens.customer

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.model.SBPSettings
import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.subscription.CancelMachineSubscriptionUseCase
import com.viwa.android.domain.subscription.LoyaltyPaymentException
import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatus
import com.viwa.android.domain.subscription.SubscriptionPaymentStatusResult
import com.viwa.android.domain.subscription.SubscriptionPayMethod
import com.viwa.android.domain.subscription.SubscriptionSaleParams
import com.viwa.android.domain.usecase.CheckSBPStatusUseCase
import com.viwa.android.domain.usecase.GetSBPLinkUseCase
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.ControllerTrafficEntry
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.hardware.controller.ViwaControllerTrafficLogger
import com.viwa.android.hardware.controller.ControllerResponseEvent
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.services.telemetry.SubscriptionLevelItem
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelTest {
    private lateinit var executor: ExecutorService
    private lateinit var mainDispatcher: kotlinx.coroutines.CoroutineDispatcher

    @Before
    fun setup() {
        executor = Executors.newSingleThreadExecutor()
        mainDispatcher = executor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
    }

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

    private suspend fun flushMain(times: Int = 12) {
        repeat(times) {
            withContext(mainDispatcher) {}
            yield()
        }
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

    private fun createTestTelemetry(): ViwaTelemetryService {
        val mock = mockk<ViwaTelemetryService>(relaxUnitFun = true)
        every { mock.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected()).asStateFlow()
        every { mock.subscribeInfo } returns MutableStateFlow(null).asStateFlow()
        every { mock.subscriptionLevels } returns MutableStateFlow(null).asStateFlow()
        every { mock.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.offlineLoyaltyDenyReason } returns
            MutableSharedFlow<OfflineAuthorizationReason>(extraBufferCapacity = 16).asSharedFlow()
        return mock
    }

    private fun createGatewayAndPaymentMocks(): Pair<ControllerGateway, ControllerSbpNotifyService> {
        val gateway = mockk<ControllerGateway>(relaxUnitFun = true)
        val responses = MutableSharedFlow<ControllerResponseEvent>(extraBufferCapacity = 16)
        every { gateway.incomingResponses } returns responses.asSharedFlow()
        every { gateway.isPhysicalControllerConnected } returns MutableStateFlow(true).asStateFlow()
        val sbp = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
        return gateway to sbp
    }

    private fun createViewModel(
        getSBPLinkUseCase: GetSBPLinkUseCase = mockk(relaxed = true),
        checkSBPStatusUseCase: CheckSBPStatusUseCase = mockk(relaxed = true),
        subscriptionUseCases: SubscriptionPaymentUseCaseMocks = relaxedSubscriptionPaymentUseCases(),
        cardPaymentOrchestrator: CardPaymentOrchestrator = mockk(relaxed = true),
        telemetryService: ViwaTelemetryService = createTestTelemetry(),
        cancelUseCaseOverride: CancelMachineSubscriptionUseCase? = null,
    ): Pair<DrinkListViewModel, SubscriptionPaymentUseCaseMocks> {
        val (gw, sbpNotify) = createGatewayAndPaymentMocks()
        val aqsi = mockk<AqsiUsbPaymentManager>(relaxed = true)
        every { aqsi.terminalStatusFlow } returns MutableStateFlow("").asStateFlow()
        val cellsRepo = mockk<TelemetryCellsRepository>(relaxUnitFun = true)
        every { cellsRepo.snapshotFlow } returns MutableStateFlow(null).asStateFlow()
        val sbpRepository = mockk<SBPRepository>(relaxUnitFun = true)
        coEvery { sbpRepository.getSettings() } returns SBPSettings(timeoutInSeconds = 120)
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
        val nano = mockk<NanoKassaRepository>(relaxUnitFun = true)
        val networkTraffic = mockk<NetworkTrafficLogger>(relaxUnitFun = true)
        every { networkTraffic.entries } returns MutableStateFlow<List<NetworkTrafficEntry>>(emptyList()).asStateFlow()
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
                aqsi,
                sbpNotify,
                telemetryService,
                getSBPLinkUseCase,
                checkSBPStatusUseCase,
                subscriptionUseCases.init,
                subscriptionUseCases.complete,
                subscriptionUseCases.apply,
                cancelUseCaseOverride ?: subscriptionUseCases.cancel,
                sbpRepository,
                nano,
                networkTraffic,
                controllerTraffic,
                cardPaymentOrchestrator,
                mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true),
            )
        return vm to subscriptionUseCases
    }

    @Test
    fun T12_1_tierPurchaseCallsPaymentInitBeforeSubscribeSale() =
        runBlocking {
            // given
            val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
            coEvery { getSbp.forSubscription(any(), any(), any()) } returns
                Result.success(SBPLink("pay-id-1", "https://qr/", "qr"))
            val checkSbp = mockk<CheckSBPStatusUseCase>(relaxUnitFun = true)
            coEvery { checkSbp.forSubscriptionPayment("pay-id-1") } returns Result.success(SBPStatus.Pending)
            val subscriptionUseCases = relaxedSubscriptionPaymentUseCases()
            coEvery { subscriptionUseCases.init(any()) } returns
                Result.success(
                    SubscriptionPaymentInit("pay-id-1", 5000, SubscriptionPaymentStatus.PENDING, "https://qr/"),
                )
            coEvery { subscriptionUseCases.apply(any(), any()) } returns Result.success(Unit)
            val (vm, applyMock) = createViewModel(getSbp, checkSbp, subscriptionUseCases)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelUuid = "770e8400-e29b-41d4-a716-446655440020",
                    subscriptionPriceRub = 50,
                    subscriptionPurchaseFlowActive = true,
                ),
            )

            // when
            vm.startSubscriptionPayment(isSbp = true)
            assertTrue(awaitCondition { vm.state.value.sbpLink != null })
            flushMain()

            // then
            coVerifyOrder {
                getSbp.forSubscription(
                    clientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                    requestUuid = any(),
                )
            }
            coVerify(exactly = 0) { applyMock.apply(any(), any()) }
        }

    @Test
    fun T12_3_sbpPaidFromStatusGet_appliesSubscribeSaleWithSamePaymentId() =
        runBlocking {
            // given
            val paymentId = "990e8400-e29b-41d4-a716-446655440040"
            val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
            coEvery { getSbp.forSubscription(any(), any(), any()) } returns
                Result.success(SBPLink(paymentId, "https://qr/", "qr"))
            val checkSbp = mockk<CheckSBPStatusUseCase>(relaxUnitFun = true)
            coEvery { checkSbp.forSubscriptionPayment(paymentId) } returns Result.success(SBPStatus.Success)
            val subscriptionUseCases = relaxedSubscriptionPaymentUseCases()
            coEvery { subscriptionUseCases.apply(any(), any()) } returns Result.success(Unit)
            val tel = createTestTelemetry()
            coEvery { tel.loadMachineRegistration() } returns
                MachineRegistration(serialNumber = "E-01", machineId = "1")
            every { tel.startSubscriptionSaleTimer(any(), any(), any(), any()) } returns Unit
            val (vm, applyMock) =
                createViewModel(getSbp, checkSbp, subscriptionUseCases, telemetryService = tel)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelUuid = "770e8400-e29b-41d4-a716-446655440020",
                    subscriptionPriceRub = 50,
                    subscriptionPurchaseFlowActive = true,
                ),
            )

            // when
            vm.startSubscriptionPayment(isSbp = true)
            assertTrue(
                awaitCondition {
                    vm.state.value.paymentSheetStep == PaymentSheetStep.SubscriptionReceipt
                },
            )
            flushMain(24)

            // then
            val saleSlot = slot<SubscriptionSaleParams>()
            coVerify(exactly = 1) {
                applyMock.apply(capture(saleSlot), SubscriptionPaymentStatus.PAID)
            }
            assertEquals(paymentId, saleSlot.captured.paymentId)
            assertEquals(SubscriptionPayMethod.SBP, saleSlot.captured.payMethod)
        }

    @Test
    fun T12_4_cardCompleteThenSubscribeSale() =
        runBlocking {
            // given
            val paymentId = "990e8400-e29b-41d4-a716-446655440040"
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Success
            val subscriptionUseCases = relaxedSubscriptionPaymentUseCases()
            coEvery { subscriptionUseCases.init(any()) } returns
                Result.success(
                    SubscriptionPaymentInit(paymentId, 5000, SubscriptionPaymentStatus.PENDING),
                )
            coEvery { subscriptionUseCases.complete(any(), any(), any()) } returns
                Result.success(
                    SubscriptionPaymentStatusResult(paymentId, SubscriptionPaymentStatus.PAID),
                )
            coEvery { subscriptionUseCases.apply(any(), any()) } returns Result.success(Unit)
            val tel = createTestTelemetry()
            coEvery { tel.loadMachineRegistration() } returns
                MachineRegistration(serialNumber = "E-01", machineId = "1")
            every { tel.startSubscriptionSaleTimer(any(), any(), any(), any()) } returns Unit
            val (vm, applyMock) =
                createViewModel(
                    subscriptionUseCases = subscriptionUseCases,
                    cardPaymentOrchestrator = orch,
                    telemetryService = tel,
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelUuid = "770e8400-e29b-41d4-a716-446655440020",
                    subscriptionPriceRub = 50,
                    subscriptionPurchaseFlowActive = true,
                ),
            )

            // when
            vm.startSubscriptionPayment(isSbp = false)
            assertTrue(
                awaitCondition {
                    vm.state.value.paymentSheetStep == PaymentSheetStep.SubscriptionReceipt
                },
            )
            flushMain(24)

            // then
            coVerifyOrder {
                subscriptionUseCases.init(any())
                subscriptionUseCases.complete(paymentId, any(), any())
                subscriptionUseCases.apply(any(), SubscriptionPaymentStatus.PAID)
            }
            val saleSlot = slot<SubscriptionSaleParams>()
            coVerify { applyMock.apply(capture(saleSlot), SubscriptionPaymentStatus.PAID) }
            assertEquals(paymentId, saleSlot.captured.paymentId)
            assertEquals(SubscriptionPayMethod.CARD, saleSlot.captured.payMethod)
        }

    @Test
    fun T12_5_paymentNotConfirmed_showsErrorWithoutLocalApply() =
        runBlocking {
            // given
            val paymentId = "990e8400-e29b-41d4-a716-446655440040"
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Success
            val subscriptionUseCases = relaxedSubscriptionPaymentUseCases()
            coEvery { subscriptionUseCases.init(any()) } returns
                Result.success(
                    SubscriptionPaymentInit(paymentId, 5000, SubscriptionPaymentStatus.PENDING),
                )
            coEvery { subscriptionUseCases.complete(any(), any(), any()) } returns
                Result.success(
                    SubscriptionPaymentStatusResult(paymentId, SubscriptionPaymentStatus.PAID),
                )
            coEvery { subscriptionUseCases.apply(any(), any()) } returns
                Result.failure(LoyaltyPaymentException("PAYMENT_NOT_CONFIRMED", "not paid"))
            val (vm, applyMock) =
                createViewModel(
                    subscriptionUseCases = subscriptionUseCases,
                    cardPaymentOrchestrator = orch,
                )
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelUuid = "770e8400-e29b-41d4-a716-446655440020",
                    subscriptionPriceRub = 50,
                    subscriptionPurchaseFlowActive = true,
                ),
            )

            // when
            vm.startSubscriptionPayment(isSbp = false)
            assertTrue(awaitCondition { vm.state.value.paymentError != null })
            flushMain()

            // then
            assertNotNull(vm.state.value.paymentError)
            assertTrue(vm.state.value.paymentError!!.contains("подтверждена"))
            coVerify(exactly = 1) { applyMock.apply(any(), SubscriptionPaymentStatus.PAID) }
            assertNull(vm.state.value.subscriptionReceiptUrl)
        }

    @Test
    fun T12_10_levelsListStillWorksBeforePurchase() =
        runBlocking {
            // given
            val levels =
                listOf(
                    SubscriptionLevelItem(
                        uuid = "770e8400-e29b-41d4-a716-446655440020",
                        price = 499.0,
                        name = "Стандарт",
                        volume = 2000,
                    ),
                )
            val tel = createTestTelemetry()
            every { tel.subscriptionLevels } returns MutableStateFlow(levels).asStateFlow()
            val (vm, _) = createViewModel(telemetryService = tel)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelsLoading = true,
                ),
            )

            // when — simulate levels.list ack via StateFlow collector in init
            flushMain(24)

            // then
            assertEquals(1, vm.state.value.subscriptionLevelsList?.size)
            assertEquals("Стандарт", vm.state.value.subscriptionLevelsList?.first()?.name)
        }
}
