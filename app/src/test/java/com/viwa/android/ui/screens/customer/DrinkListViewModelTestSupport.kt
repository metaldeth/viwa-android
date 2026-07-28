package com.viwa.android.ui.screens.customer

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.network.NetworkTrafficEntry
import com.viwa.android.data.network.NetworkTrafficLogger
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.SBPSettings
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.repository.TelemetryCellsRepository
import com.viwa.android.domain.subscription.CancelMachineSubscriptionUseCase
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
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.services.telemetry.ViwaTelemetryService
import com.viwa.android.domain.telemetry.HoldPourTelemetryCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DrinkListViewModelTestSupport {
    fun createTestTelemetry(): ViwaTelemetryService {
        val mock = mockk<ViwaTelemetryService>(relaxUnitFun = true)
        every { mock.connectionState } returns
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected()).asStateFlow()
        every { mock.subscribeInfo } returns MutableStateFlow(null).asStateFlow()
        every { mock.subscriptionLevels } returns MutableStateFlow(null).asStateFlow()
        every { mock.loyaltyCardClientScans } returns
            MutableSharedFlow<String>(extraBufferCapacity = 16).asSharedFlow()
        every { mock.invalidLoyaltyCardScans } returns
            MutableSharedFlow<Unit>(extraBufferCapacity = 16).asSharedFlow()
        return mock
    }

    fun vmConfigRepo(): ConfigRepository =
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

    fun createViewModel(
        getSBPLinkUseCase: GetSBPLinkUseCase = mockk(relaxed = true),
        checkSBPStatusUseCase: CheckSBPStatusUseCase = mockk(relaxed = true),
        subscriptionUseCases: SubscriptionPaymentUseCaseMocks = relaxedSubscriptionPaymentUseCases(),
        cardPaymentOrchestrator: CardPaymentOrchestrator = mockk(relaxed = true),
        telemetryService: ViwaTelemetryService = createTestTelemetry(),
        cancelUseCaseOverride: CancelMachineSubscriptionUseCase? = null,
    ): Pair<DrinkListViewModel, SubscriptionPaymentUseCaseMocks> {
        val gateway = mockk<ControllerGateway>(relaxUnitFun = true)
        val responses = MutableSharedFlow<ControllerResponseEvent>(extraBufferCapacity = 16)
        every { gateway.incomingResponses } returns responses.asSharedFlow()
        every { gateway.isPhysicalControllerConnected } returns MutableStateFlow(true).asStateFlow()
        val sbpNotify = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
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
                gateway,
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
}
