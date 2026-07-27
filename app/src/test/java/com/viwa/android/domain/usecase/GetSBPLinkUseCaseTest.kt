package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.domain.subscription.InitMachineSubscriptionPaymentUseCase
import com.viwa.android.domain.subscription.SubscriptionPaymentInit
import com.viwa.android.domain.subscription.SubscriptionPaymentInitParams
import com.viwa.android.domain.subscription.SubscriptionPaymentStatus
import com.viwa.android.domain.subscription.SubscriptionPayMethod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetSBPLinkUseCaseTest {
    @Test
    fun T12_7_forSubscription_doesNotCallLocalBillingRepository() =
        runTest {
            // given
            val sbpRepo = mockk<SBPRepository>(relaxed = true)
            val initPayment = mockk<InitMachineSubscriptionPaymentUseCase>(relaxed = true)
            val params =
                SubscriptionPaymentInitParams(
                    clientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                    payMethod = SubscriptionPayMethod.SBP,
                    requestUuid = "880e8400-e29b-41d4-a716-446655440030",
                )
            coEvery { initPayment(params) } returns
                Result.success(
                    SubscriptionPaymentInit(
                        paymentId = "990e8400-e29b-41d4-a716-446655440040",
                        amountKopecks = 49900,
                        status = SubscriptionPaymentStatus.PENDING,
                        sbpQrUrl = "https://sbp.example/qr",
                    ),
                )
            val useCase = GetSBPLinkUseCase(sbpRepo, initPayment)

            // when
            val result = useCase.forSubscription(params)

            // then
            assertTrue(result.isSuccess)
            assertEquals(
                SBPLink(
                    orderId = "990e8400-e29b-41d4-a716-446655440040",
                    url = "https://sbp.example/qr",
                    qrData = "https://sbp.example/qr",
                ),
                result.getOrNull(),
            )
            coVerify(exactly = 0) { sbpRepo.getSBPLink(any()) }
            coVerify(exactly = 1) { initPayment(params) }
        }
}
