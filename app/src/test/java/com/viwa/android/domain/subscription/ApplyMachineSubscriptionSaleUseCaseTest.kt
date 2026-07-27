package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyMachineSubscriptionSaleUseCaseTest {
    @Test
    fun T12_2_pendingPaymentStatus_doesNotSendSubscribeSale() =
        runTest {
            // given
            val repo = mockk<MachineSubscriptionPaymentRepository>(relaxed = true)
            val useCase = ApplyMachineSubscriptionSaleUseCase(repo)
            val params =
                SubscriptionSaleParams(
                    paymentId = "990e8400-e29b-41d4-a716-446655440040",
                    requestUuid = "880e8400-e29b-41d4-a716-446655440030",
                    clientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                    payMethod = SubscriptionPayMethod.SBP,
                )

            // when
            val result = useCase(params, SubscriptionPaymentStatus.PENDING)

            // then
            assertTrue(result.isFailure)
            coVerify(exactly = 0) { repo.applySubscriptionSale(any()) }
        }

    @Test
    fun paidPaymentStatus_sendsSubscribeSale() =
        runTest {
            // given
            val repo = mockk<MachineSubscriptionPaymentRepository>(relaxed = true)
            coEvery { repo.applySubscriptionSale(any()) } returns Result.success(Unit)
            val useCase = ApplyMachineSubscriptionSaleUseCase(repo)
            val params =
                SubscriptionSaleParams(
                    paymentId = "990e8400-e29b-41d4-a716-446655440040",
                    requestUuid = "880e8400-e29b-41d4-a716-446655440030",
                    clientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                    payMethod = SubscriptionPayMethod.SBP,
                )

            // when
            val result = useCase(params, SubscriptionPaymentStatus.PAID)

            // then
            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { repo.applySubscriptionSale(params) }
        }
}
