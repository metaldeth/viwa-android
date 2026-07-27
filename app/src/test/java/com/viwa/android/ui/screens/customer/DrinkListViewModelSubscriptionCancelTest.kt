package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import com.viwa.android.domain.subscription.CancelMachineSubscriptionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListViewModelSubscriptionCancelTest {
    private lateinit var executor: ExecutorService

    @Before
    fun setup() {
        executor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(executor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        executor.shutdownNow()
    }

    @Test
    fun T12_8_cancelFlowSendsSubscribeCancelWithRequestUuid() =
        runBlocking {
            // given
            val paymentId = "990e8400-e29b-41d4-a716-446655440040"
            val requestUuid = "880e8400-e29b-41d4-a716-446655440030"
            val clientId = "660e8400-e29b-41d4-a716-446655440010"
            val paymentRepo = mockk<MachineSubscriptionPaymentRepository>(relaxed = true)
            val cancelLatch = CountDownLatch(1)
            coEvery { paymentRepo.cancelSubscription(clientId, requestUuid) } coAnswers {
                cancelLatch.countDown()
                Result.success(Unit)
            }
            val cancelUseCase = CancelMachineSubscriptionUseCase(paymentRepo)
            val (vm, _) = DrinkListViewModelTestSupport.createViewModel(cancelUseCaseOverride = cancelUseCase)
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    scannedSubscriptionClientId = clientId,
                    subscriptionLevelUuid = "770e8400-e29b-41d4-a716-446655440020",
                    subscriptionPriceRub = 50,
                    subscriptionPurchaseFlowActive = true,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Sbp,
                    sbpLink = SBPLink(paymentId, "https://qr/", "qr"),
                ),
            )
            vm.setSubscriptionPaymentSessionForTests(paymentId, requestUuid)

            // when
            vm.dismissPaymentSheet()
            assertTrue(cancelLatch.await(5, TimeUnit.SECONDS))

            // then
            coVerify(exactly = 1) { paymentRepo.cancelSubscription(clientId, requestUuid) }
        }
}
