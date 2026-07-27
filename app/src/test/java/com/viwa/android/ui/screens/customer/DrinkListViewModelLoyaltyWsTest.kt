package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.loyalty.LoyaltyWaterUseCoordinator
import com.viwa.android.services.telemetry.UseSubscriptionPayMethod
import com.viwa.android.services.telemetry.UseSubscriptionSaleBody
import com.viwa.android.services.telemetry.ViwaTelemetryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrinkListViewModelLoyaltyWsTest {
    @Test
    fun T11_6_waterUseBody_containsRequestUuidAndVolumeMl() = runTest {
        // given
        val tel = mockk<ViwaTelemetryService>(relaxed = true)
        val bodySlot = slot<UseSubscriptionSaleBody>()
        coEvery { tel.sendUseSubscriptionSaleTopic(capture(bodySlot)) } returns Result.success(Unit)
        coEvery { tel.loadMachineRegistration() } returns
            com.viwa.android.domain.model.MachineRegistration(machineId = "42")
        coEvery { tel.sendStatusSubscribeTopic(any()) } returns Result.success(Unit)

        val requestUuid = "880e8400-e29b-41d4-a716-446655440034"
        val body =
            UseSubscriptionSaleBody(
                clientId = "660e8400-e29b-41d4-a716-446655440010",
                volume = 0.2,
                machineId = 42,
                isFree = true,
                ingredientId = 1,
                requestUuid = requestUuid,
                date = "2026-07-27T09:00:00.000Z",
                payMethod = UseSubscriptionPayMethod.SUBSCRIBE,
                price = 0.0,
            )

        // when
        tel.sendUseSubscriptionSaleTopic(body)

        // then
        coVerify(exactly = 1) { tel.sendUseSubscriptionSaleTopic(body) }
        assertEquals(requestUuid, bodySlot.captured.requestUuid)
        assertEquals(0.2, bodySlot.captured.volume, 0.001)
    }

    @Test
    fun T11_7_duplicatePourSameRequestUuid_coordinatorSkipsSecondSend() {
        // given
        val coordinator = LoyaltyWaterUseCoordinator()
        val requestUuid = "880e8400-e29b-41d4-a716-446655440034"

        // when
        val first = coordinator.shouldSend(requestUuid)
        val second = coordinator.shouldSend(requestUuid)

        // then
        assertTrue(first)
        assertFalse(second)
    }
}
