package com.viwa.android.services.telemetry

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryLoyaltySyncHandler
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.SimpleTelemetryCoordinator
import com.viwa.android.data.remote.telemetry.v3.TelemetryDispenseSyncCoordinator
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.data.telemetry.loyalty.LoyaltyWsCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    private fun createService(
        wsManager: MvpTelemetryWebSocketManager = mockk(relaxed = true),
        configRepository: ConfigRepository = mockk(relaxed = true),
    ): Pair<ViwaTelemetryService, MvpTelemetryLoyaltySyncHandler> {
        coEvery { configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) } returns "false"
        var handler: MvpTelemetryLoyaltySyncHandler? = null
        every { wsManager.loyaltySyncHandler = any() } answers {
            handler = firstArg()
            Unit
        }
        every { wsManager.loyaltySyncHandler } answers { handler }
        val service =
            ViwaTelemetryService(
                configRepository = configRepository,
                mvpCoordinator = mockk<SimpleTelemetryCoordinator>(relaxed = true),
                wsManager = wsManager,
                dispenseSyncCoordinator = mockk(relaxed = true),
                offlinePourAuthorizationService = mockk(relaxed = true),
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            )
        return service to requireNotNull(handler)
    }

    @Test
    fun T11_4_sendStatusGet_sendsLoyaltyStatusGetEnvelope() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), any()) } returns
            Result.success("msg-status")

        val (service, _) = createService(wsManager)
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
        val (service, handler) = createService(wsManager)
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
    fun T11_8_levelsListAck_populatesSubscriptionLevelsStateFlow() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val (service, handler) = createService(wsManager)
        advanceUntilIdle()
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_LEVELS_LIST, any(), capture(messageIdSlot))
        } returns Result.success("unused")

        service.sendSubscriptionLevelRequest()

        val payload =
            buildJsonObject {
                put(
                    "levels",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "770e8400-e29b-41d4-a716-446655440020")
                                put("name", "Стандарт")
                                put("dailyVolumeMl", 2000)
                                put("priceKopecks", 49900)
                            },
                        )
                    },
                )
            }

        // when
        handler.onLoyaltyAck(messageIdSlot.captured, payload)
        advanceUntilIdle()

        // then
        val levels = service.subscriptionLevels.value
        assertNotNull(levels)
        assertEquals(1, levels!!.size)
        assertEquals("770e8400-e29b-41d4-a716-446655440020", levels.first().uuid)
        assertEquals("Стандарт", levels.first().name)
        assertEquals(2000, levels.first().volume)
    }

    @Test
    fun T11_10_legacyStatusSubscribeTopic_callsWsManager() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        coEvery { wsManager.sendEnvelope(any(), any(), any()) } returns Result.success("msg-legacy")
        val dispenseSyncCoordinator = mockk<TelemetryDispenseSyncCoordinator>(relaxed = true)
        val configRepository = mockk<ConfigRepository>(relaxed = true)
        coEvery { configRepository.get(JsonStoreKeys.TELEMETRY_PAUSED_BY_USER) } returns "false"
        var handler: MvpTelemetryLoyaltySyncHandler? = null
        every { wsManager.loyaltySyncHandler = any() } answers {
            handler = firstArg()
            Unit
        }
        every { wsManager.loyaltySyncHandler } answers { handler }
        val service =
            ViwaTelemetryService(
                configRepository = configRepository,
                mvpCoordinator = mockk<SimpleTelemetryCoordinator>(relaxed = true),
                wsManager = wsManager,
                dispenseSyncCoordinator = dispenseSyncCoordinator,
                offlinePourAuthorizationService = mockk(relaxed = true),
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            )
        advanceUntilIdle()

        // when
        service.sendStatusSubscribeTopic("660e8400-e29b-41d4-a716-446655440010")
        service.sendSubscriptionLevelRequest()
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
        coVerify { wsManager.sendEnvelope(LoyaltyWsCodec.TYPE_LEVELS_LIST, any(), any()) }
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
        val (service, handler) = createService(wsManager)
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
    fun T12_subscribeCancel_sendsLoyaltySubscribeCancelEnvelope() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), capture(messageIdSlot))
        } returns Result.success("msg-cancel")
        val (service, handler) = createService(wsManager)
        advanceUntilIdle()

        val ackPayload =
            buildJsonObject {
                put("clientId", "660e8400-e29b-41d4-a716-446655440010")
                put("active", false)
                put("dailyRemainingMl", 0)
            }

        // when
        var result: Result<Unit>? = null
        val job =
            launch {
                result =
                    service.sendSubscribeCancel(
                        clientId = "660e8400-e29b-41d4-a716-446655440010",
                        requestUuid = "880e8400-e29b-41d4-a716-446655440033",
                    )
            }
        advanceUntilIdle()
        handler.onLoyaltyAck(messageIdSlot.captured, ackPayload)
        job.join()
        advanceUntilIdle()

        // then
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertEquals(LoyaltyWsCodec.TYPE_SUBSCRIBE_CANCEL, typeSlot.captured)
        assertEquals(
            "880e8400-e29b-41d4-a716-446655440033",
            payloadSlot.captured["requestUuid"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun T12_paymentInit_sendsLoyaltyPaymentInitEnvelope() = runTest {
        // given
        val wsManager = mockk<MvpTelemetryWebSocketManager>(relaxed = true)
        val typeSlot = slot<String>()
        val payloadSlot = slot<kotlinx.serialization.json.JsonObject>()
        val messageIdSlot = slot<String>()
        coEvery {
            wsManager.sendEnvelope(capture(typeSlot), capture(payloadSlot), capture(messageIdSlot))
        } returns Result.success("msg-pay-init")
        val (service, handler) = createService(wsManager)
        advanceUntilIdle()

        val ackPayload =
            buildJsonObject {
                put("paymentId", "990e8400-e29b-41d4-a716-446655440040")
                put("amountKopecks", 49900)
                put("status", "PENDING")
                put("sbpQrUrl", "https://sbp.example/qr")
            }

        // when
        var result: Result<com.viwa.android.domain.subscription.SubscriptionPaymentInit>? = null
        val job =
            launch {
                result =
                    service.sendPaymentInit(
                        com.viwa.android.domain.subscription.SubscriptionPaymentInitParams(
                            clientId = "660e8400-e29b-41d4-a716-446655440010",
                            subscriptionLevelId = "770e8400-e29b-41d4-a716-446655440020",
                            payMethod = com.viwa.android.domain.subscription.SubscriptionPayMethod.SBP,
                            requestUuid = "880e8400-e29b-41d4-a716-446655440030",
                        ),
                    )
            }
        advanceUntilIdle()
        handler.onLoyaltyAck(messageIdSlot.captured, ackPayload)
        job.join()
        advanceUntilIdle()

        // then
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertEquals(LoyaltyWsCodec.TYPE_PAYMENT_INIT, typeSlot.captured)
        assertEquals(
            "660e8400-e29b-41d4-a716-446655440010",
            payloadSlot.captured["clientId"]!!.jsonPrimitive.content,
        )
        assertEquals("990e8400-e29b-41d4-a716-446655440040", result!!.getOrThrow().paymentId)
    }
}
