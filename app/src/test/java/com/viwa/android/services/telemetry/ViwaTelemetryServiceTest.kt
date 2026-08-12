package com.viwa.android.services.telemetry

import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViwaTelemetryServiceTest {
    @Test
    fun T11_4_sendStatusGet_sendsLoyaltyStatusGetEnvelope() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), any()) } returns
            Result.success("msg-status")

        val (service, _) = createViwaTelemetryServiceForTests(wsManager = wsManager)
        advanceUntilIdle()

        // when
        val result = service.sendStatusGet("660e8400-e29b-41d4-a716-446655440010")

        // then
        assertTrue(result.isSuccess)
        assertEquals(LoyaltyWsCodec.TYPE_STATUS_GET, typeSlot.captured)
        assertEquals(
            "660e8400-e29b-41d4-a716-446655440010",
            payloadSlot.captured["clientId"]!!.jsonPrimitive.content,
        )
        coVerify(exactly = 1) { wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), any()) }
    }

    @Test
    fun T11_5_inboundStatusAck_updatesSubscribeInfoStateFlow() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val (service, handler) = createViwaTelemetryServiceForTests(wsManager = wsManager)
        advanceUntilIdle()
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), capture(messageIdSlot))
        } returns Result.success("unused")

        service.sendStatusGet("660e8400-e29b-41d4-a716-446655440010")

        val payload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", true)
                put("dailyLimitMl", 2000)
                put("dailyRemainingMl", 450)
                put("limitExhausted", false)
            }

        // when
        handler.onLoyaltyAck(messageIdSlot.captured, payload)
        advanceUntilIdle()

        // then
        val info = service.subscribeInfo.value
        assertNotNull(info)
        assertEquals("660e8400-e29b-41d4-a716-446655440010", info!!.clientId)
        assertEquals(450, info.volumeMl)
    }

    @Test
    fun T11_10_legacyStatusSubscribeTopic_callsWsManager() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg-legacy")
        val dispenseSyncCoordinator = mockk<TelemetryDispenseSyncCoordinator>(relaxed = true)
        val (service, _) =
            createViwaTelemetryServiceForTests(
                wsManager = wsManager,
                dispenseSyncCoordinator = dispenseSyncCoordinator,
            )
        advanceUntilIdle()

        // when
        service.sendStatusSubscribeTopic("660e8400-e29b-41d4-a716-446655440010")
        service.sendUseSubscriptionSaleTopic(
            UseSubscriptionSaleBody(
                clientId = "660e8400-e29b-41d4-a716-446655440010",
                volume = 0.2,
                machineId = 1,
                isFree = true,
                ingredientId = 1,
                requestUuid = "880e8400-e29b-41d4-a716-446655440034",
                date = "2026-07-27T09:00:00.000Z",
                payMethod = UseSubscriptionPayMethod.SUBSCRIBE,
                price = 0.0,
            ),
        )

        // then
        coVerify { wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), any()) }
        coVerify {
            dispenseSyncCoordinator.enqueuePourReport(
                match {
                    it.requestUuid == "880e8400-e29b-41d4-a716-446655440034" &&
                        it.volumeMl == 200 &&
                        it.plainWaterType == "FILTERED"
                },
            )
        }
    }

    @Test
    fun T12_9_inboundStatusChanged_updatesSubscribeInfoWithoutRescan() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val (service, handler) = createViwaTelemetryServiceForTests(wsManager = wsManager)
        advanceUntilIdle()

        val payload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", true)
                put("dailyLimitMl", 2000)
                put("dailyRemainingMl", 250)
                put("tierName", "Стандарт")
                put("limitExhausted", false)
            }

        // when
        handler.onStatusChanged(payload)
        advanceUntilIdle()

        // then
        val info = service.subscribeInfo.value
        assertNotNull(info)
        assertEquals(250, info!!.volumeMl)
        assertEquals("660e8400-e29b-41d4-a716-446655440010", info.clientId)
        coVerify(exactly = 0) { wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), any()) }
    }

    @Test
    fun inboundPourReportBalanceAck_mergesPartialRemainingWithoutClearingClient() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), capture(messageIdSlot))
        } returns Result.success("unused")
        val (service, handler) = createViwaTelemetryServiceForTests(wsManager = wsManager)
        advanceUntilIdle()
        service.sendStatusGet("660e8400-e29b-41d4-a716-446655440010")
        val statusPayload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", true)
                put("dailyLimitMl", 2000)
                put("dailyRemainingMl", 450)
                put("subscriptionEndsAt", "2026-08-31T00:00:00.000Z")
                put("limitExhausted", false)
            }
        handler.onLoyaltyAck(messageIdSlot.captured, statusPayload)
        advanceUntilIdle()

        val pourAckPayload = buildJsonObject { put("dailyRemainingMl", 220) }

        // when
        handler.onPourReportBalanceAck(pourAckPayload)
        advanceUntilIdle()

        // then
        val info = service.subscribeInfo.value
        assertNotNull(info)
        assertEquals(220, info!!.volumeMl)
        assertEquals(2000, info.maxVolumeMl)
        assertEquals("660e8400-e29b-41d4-a716-446655440010", info.clientId)
        assertEquals("2026-08-31T00:00:00.000Z", info.subscribeDateEnd)
    }

    @Test
    fun applyOptimisticSubscriptionPourDeduction_reducesRemainingMl() = runTest {
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_STATUS_GET, any(), capture(messageIdSlot))
        } returns Result.success("unused")
        val (service, handler) = createViwaTelemetryServiceForTests(wsManager = wsManager)
        advanceUntilIdle()
        service.sendStatusGet("660e8400-e29b-41d4-a716-446655440010")
        handler.onLoyaltyAck(
            messageIdSlot.captured,
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", true)
                put("dailyLimitMl", 2000)
                put("dailyRemainingMl", 500)
                put("subscriptionEndsAt", "2026-08-31T00:00:00.000Z")
                put("limitExhausted", false)
            },
        )
        advanceUntilIdle()

        service.applyOptimisticSubscriptionPourDeduction(92)

        assertEquals(408, service.subscribeInfo.value?.volumeMl)
    }
}
