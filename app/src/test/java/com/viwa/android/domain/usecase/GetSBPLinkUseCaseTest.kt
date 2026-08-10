package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.repository.SBPRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetSBPLinkUseCaseTest {
    @Test
    fun invoke_delegatesToSbpRepositoryForDrinkPurchase() =
        runTest {
            val sbpRepo = mockk<SBPRepository>(relaxed = true)
            coEvery { sbpRepo.getSBPLink(12_300) } returns
                Result.success(SBPLink("order-1", "https://sbp.example/qr", "qr-data"))
            val useCase = GetSBPLinkUseCase(sbpRepo)

            val result = useCase(12_300)

            assertTrue(result.isSuccess)
            assertEquals(
                SBPLink("order-1", "https://sbp.example/qr", "qr-data"),
                result.getOrNull(),
            )
            coVerify(exactly = 1) { sbpRepo.getSBPLink(12_300) }
        }
}
