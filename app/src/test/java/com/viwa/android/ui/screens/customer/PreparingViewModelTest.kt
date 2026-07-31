package com.viwa.android.ui.screens.customer

import androidx.lifecycle.SavedStateHandle
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.repository.NanoKassaRepository
import com.viwa.android.services.preparing.CustomerPreparingPhase
import com.viwa.android.services.preparing.PreparingManager
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparingViewModelTest {
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun resetSession_clearsSubscribeUiStateWhenPayMethodIsSubscribe() = runTest {
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
        val telemetry = mockk<ViwaTelemetryService>(relaxUnitFun = true)
        val config = mockk<ConfigRepository>(relaxed = true)
        val nano = mockk<NanoKassaRepository>(relaxed = true)
        val savedState =
            SavedStateHandle(
                mapOf(
                    "payMethod" to "subscribe",
                    "priceRub" to 0,
                    "productName" to "Cola",
                ),
            )
        val vm =
            PreparingViewModel(
                savedStateHandle = savedState,
                preparingManager = preparing,
                nanoKassaRepository = nano,
                configRepository = config,
                telemetryService = telemetry,
            )
        advanceUntilIdle()

        vm.resetSession()

        verify(exactly = 1) { telemetry.clearSubscribeUiState() }
        verify(exactly = 1) { preparing.resetSession() }
    }

    @Test
    fun subscribePourWithCatalogPrice_skipsFiscalReceipt() = runTest {
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        val phase = MutableStateFlow<CustomerPreparingPhase>(CustomerPreparingPhase.Idle)
        every { preparing.customerPhase } returns phase.asStateFlow()
        val telemetry = mockk<ViwaTelemetryService>(relaxUnitFun = true)
        val config = mockk<ConfigRepository>(relaxed = true)
        val nano = mockk<NanoKassaRepository>(relaxed = true)
        coEvery { nano.hasNanoFiscalConfig() } returns true
        val savedState =
            SavedStateHandle(
                mapOf(
                    "payMethod" to "subscribe",
                    "priceRub" to 150,
                    "productName" to "Cola",
                ),
            )
        val vm =
            PreparingViewModel(
                savedStateHandle = savedState,
                preparingManager = preparing,
                nanoKassaRepository = nano,
                configRepository = config,
                telemetryService = telemetry,
            )
        phase.value = CustomerPreparingPhase.DrinkReady
        advanceUntilIdle()

        assertEquals(ReceiptAfterReadyState.SuccessCheckmark, vm.receiptAfterReady.value)
        coVerify(exactly = 0) { nano.sendFiscalReceipt(any(), any(), any(), any()) }
    }

    @Test
    fun resetSession_doesNotClearSubscribeForPaidPour() = runTest {
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        every { preparing.customerPhase } returns
            MutableStateFlow(CustomerPreparingPhase.Idle).asStateFlow()
        val telemetry = mockk<ViwaTelemetryService>(relaxUnitFun = true)
        val config = mockk<ConfigRepository>(relaxed = true)
        val nano = mockk<NanoKassaRepository>(relaxed = true)
        val savedState =
            SavedStateHandle(
                mapOf(
                    "payMethod" to "sbp",
                    "priceRub" to 100,
                    "productName" to "Cola",
                ),
            )
        val vm =
            PreparingViewModel(
                savedStateHandle = savedState,
                preparingManager = preparing,
                nanoKassaRepository = nano,
                configRepository = config,
                telemetryService = telemetry,
            )
        advanceUntilIdle()

        vm.resetSession()

        verify(exactly = 0) { telemetry.clearSubscribeUiState() }
    }
}
