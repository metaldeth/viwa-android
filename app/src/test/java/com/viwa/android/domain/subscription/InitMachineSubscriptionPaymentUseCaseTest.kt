package com.viwa.android.domain.subscription

import com.viwa.android.domain.repository.MachineSubscriptionPaymentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitMachineSubscriptionPaymentUseCaseTest {
    @Test
    fun T12_6_sameRequestUuid_reusesInitPaymentIdempotency() =
        runTest {
            // given
            val repo = mockk<MachineSubscriptionPaymentRepository>(relaxed = true)
            val requestUuid = "880e8400-e29b-41d4-a716-446655440030"
            val params =
                SubscriptionPaymentInitParams(
                    clientId = "660e8400-e29b-41d4-a716-446655440010",
                    subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                    payMethod = SubscriptionPayMethod.SBP,
                    requestUuid = requestUuid,
                )
            val init =
                SubscriptionPaymentInit(
                    paymentId = "990e8400-e29b-41d4-a716-446655440040",
                    amountKopecks = 49900,
                    status = SubscriptionPaymentStatus.PENDING,
                    sbpQrUrl = "https://sbp.example/qr",
                )
            coEvery { repo.initPayment(params) } returns Result.success(init)
            val useCase = InitMachineSubscriptionPaymentUseCase(repo)

            // when
            val first = useCase(params)
            val second = useCase(params)

            // then
            assertTrue(first.isSuccess)
            assertTrue(second.isSuccess)
            assertEquals(requestUuid, params.requestUuid)
            assertEquals(init.paymentId, first.getOrNull()?.paymentId)
            assertEquals(init.paymentId, second.getOrNull()?.paymentId)
            coVerify(exactly = 2) { repo.initPayment(params) }
        }
}
