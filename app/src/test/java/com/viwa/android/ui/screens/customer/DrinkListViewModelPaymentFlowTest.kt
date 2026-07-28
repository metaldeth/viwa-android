package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.payment.TerminalProductType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelPaymentFlowTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleContainer(containerNumber: Int = 2): DrinkContainer {
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
            containerNumber = containerNumber,
            sodaStatus = null,
            product = product,
            productUuid = "test-product-uuid",
            volumeMl = 1000,
            minVolumeMl = 0,
            isActive = true,
        )
    }

    @Test
    fun task05_9_drinkCardUsesOrchestratorNotDirectTerminal() =
        runTest(scheduler) {
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Success
            val sbp = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
            DrinkListCardPaymentFlow.runDrinkPaymentBeforePour(
                sampleContainer(3),
                volume = 300,
                sbp = false,
                cardPaymentOrchestrator = orch,
                controllerSbpNotifyService = sbp,
            )
            coVerify(exactly = 1) {
                orch.pay(TerminalProductType.Drink, price = 100, productNumber = 3, sbp = false)
            }
            coVerify(exactly = 0) { sbp.notifySbpPayment(any(), any(), any()) }
        }

    @Test
    fun task05_10_drinkSbpDoesNotCallCardOrchestratorPay() =
        runTest(scheduler) {
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            val sbp = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
            DrinkListCardPaymentFlow.runDrinkPaymentBeforePour(
                sampleContainer(3),
                volume = 300,
                sbp = true,
                cardPaymentOrchestrator = orch,
                controllerSbpNotifyService = sbp,
            )
            coVerify(exactly = 1) {
                sbp.notifySbpPayment(
                    TerminalProductType.Drink,
                    price = 100,
                    productNumber = 3,
                )
            }
            coVerify(exactly = 0) { orch.pay(any(), any(), any(), any()) }
        }

    @Test
    fun task05_11_subscriptionCardCallsOrchestratorLikeLegacyTerminal() =
        runTest(scheduler) {
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Success
            DrinkListCardPaymentFlow.paySubscriptionWithCard(84, orch)
            coVerify(exactly = 1) {
                orch.pay(TerminalProductType.Drink, price = 84, productNumber = 0, sbp = false)
            }
        }
}
