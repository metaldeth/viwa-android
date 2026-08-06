package com.viwa.android.ui.screens.customer

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.domain.customer.TelemetryCellsSnapshotAdapter
import com.viwa.android.domain.model.TelemetryCell
import com.viwa.android.domain.model.TelemetryCellsSnapshot
import com.viwa.android.domain.model.TelemetryProduct
import com.viwa.android.domain.offline.OfflineAuthorizationReason
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.usecase.CheckSBPStatusUseCase
import com.viwa.android.domain.usecase.GetSBPLinkUseCase
import com.viwa.android.hardware.controller.ControllerGateway
import com.viwa.android.hardware.controller.FlowTemperatureStore
import com.viwa.android.hardware.controller.ControllerTrafficEntry
import com.viwa.android.hardware.controller.ViwaControllerTrafficLogger
import com.viwa.android.hardware.controller.ControllerResponseEvent
import com.viwa.android.services.payment.CardPaymentEventLogger
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelMvpInventoryTest {
    private lateinit var executor: ExecutorService

    @Before
    fun setup() {
        executor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(executor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        runBlocking {
            DrinkListViewModelTestSupport.clearTrackedViewModels(executor.asCoroutineDispatcher())
        }
        Dispatchers.resetMain()
        executor.shutdown()
        if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private suspend fun flushMain(times: Int = 16) {
        repeat(times) {
            withContext(Dispatchers.Main) {}
            yield()
        }
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 5000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            flushMain(1)
        }
        return false
    }

    private class FakeConfigRepository : ConfigRepository {
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
        val mock = mockk<ViwaTelemetryService>(relaxed = true)
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
        configRepository: ConfigRepository,
        telemetryCellsRepository: TelemetryCellsRepository,
    ): DrinkListViewModel {
        val telemetry = createTestTelemetry()
        val payScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val (gateway, sbpNotify) = createGatewayAndPaymentMocks()
        val aqsi = mockk<AqsiUsbPaymentManager>(relaxed = true)
        every { aqsi.terminalStatusFlow } returns MutableStateFlow("").asStateFlow()
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(com.viwa.android.services.preparing.CustomerPreparingPhase.Idle).asStateFlow()
        val getSbp = mockk<GetSBPLinkUseCase>(relaxUnitFun = true)
        val checkSbp = mockk<CheckSBPStatusUseCase>(relaxUnitFun = true)
        val subscriptionUseCases = relaxedSubscriptionPaymentUseCases()
        val sbp = DrinkListViewModelTestSupport.sbpRepositoryMock()
        val nano = DrinkListViewModelTestSupport.nanoKassaRepositoryMock()
        val networkTraffic = mockk<NetworkTrafficLogger>(relaxUnitFun = true)
        every { networkTraffic.entries } returns MutableStateFlow<List<NetworkTrafficEntry>>(emptyList()).asStateFlow()
        val controllerTraffic = mockk<ViwaControllerTrafficLogger>(relaxUnitFun = true)
        every { controllerTraffic.entries } returns MutableStateFlow<List<ControllerTrafficEntry>>(emptyList()).asStateFlow()
        val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
        val vm =
            DrinkListViewModel(
            configRepository,
            telemetryCellsRepository,
            preparing,
            gateway,
            FlowTemperatureStore(),
            aqsi,
            sbpNotify,
            telemetry,
            getSbp,
            checkSbp,
            subscriptionUseCases.init,
            subscriptionUseCases.complete,
            subscriptionUseCases.apply,
            subscriptionUseCases.cancel,
            sbp,
            nano,
            networkTraffic,
            controllerTraffic,
            orch,
            mockk<HoldPourTelemetryCoordinator>(relaxUnitFun = true),
        )
        DrinkListViewModelTestSupport.trackViewModel(vm)
        return vm
    }

    private fun mvpSnapshot(): TelemetryCellsSnapshot =
        TelemetryCellsSnapshot(
            products = listOf(TelemetryProduct("prod-mvp", "MVP Cola", "cherry")),
            cells =
                listOf(
                    TelemetryCell(
                        uuid = "cell-mvp",
                        cellNumber = 4,
                        productUuid = "prod-mvp",
                        productName = "MVP Cola",
                        tasteMediaKey = "cherry",
                        volume = 800,
                        maxVolume = 5000,
                        blockVolume = 0,
                        sosVolume = 100,
                        dosage1Price = 12000,
                        dosage2Price = 18000,
                    ),
                ),
        )

    @Test
    fun snapshotFlow_updatesDrinkContainers() =
        runBlocking {
            val snapshotFlow = MutableStateFlow<TelemetryCellsSnapshot?>(null)
            val cellsRepo = mockk<TelemetryCellsRepository>()
            every { cellsRepo.snapshotFlow } returns snapshotFlow.asStateFlow()
            val vm = createViewModel(FakeConfigRepository(), cellsRepo)

            snapshotFlow.value = mvpSnapshot()
            assertTrue(awaitCondition { vm.state.value.containers.isNotEmpty() })
            flushMain()

            val expected = TelemetryCellsSnapshotAdapter.toDrinkContainers(mvpSnapshot())
            assertEquals(expected, vm.state.value.containers)
            assertEquals("MVP Cola", vm.state.value.containers.first().product.name)
        }
}
