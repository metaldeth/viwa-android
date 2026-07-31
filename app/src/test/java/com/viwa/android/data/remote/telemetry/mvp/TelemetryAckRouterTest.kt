package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.local.outbox.CommerceOutboxStore
import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence
import com.viwa.android.data.local.outbox.MachineOutboxKind
import com.viwa.android.data.local.outbox.MachineOutboxStatus
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator
import com.viwa.android.data.local.outbox.PourOutboxStore
import com.viwa.android.data.local.outbox.TestOutboxFixtures
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.telemetry.PlainWaterType
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryAckRouterTest {
    private lateinit var persistence: FakeMachineOutboxPersistence
    private lateinit var outboxStore: MachineOutboxStore
    private lateinit var commerceOutboxStore: CommerceOutboxStore
    private lateinit var pourOutboxStore: PourOutboxStore
    private lateinit var router: TelemetryAckRouter

    @Before
    fun setUp() {
        persistence = FakeMachineOutboxPersistence()
        val config = mockk<ConfigRepository>(relaxed = true)
        outboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = config,
                migrator = PendingSalesOutboxMigrator(persistence, config),
            )
        commerceOutboxStore = CommerceOutboxStore(outboxStore)
        pourOutboxStore = PourOutboxStore(outboxStore)
        router = TelemetryAckRouter(outboxStore)
    }

    @Test
    fun `routes transactionId ack to outbox when generation matches`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "tx-abc")
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 1L)
        val payload = buildJsonObject { put("transactionId", "tx-abc"); put("ok", true) }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-1",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = "unknown-corr",
            )
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 1L,
                cellsHandler = null,
                loyaltyHandler = null,
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
    }

    @Test
    fun `rejects stale transactionId ack from prior session generation`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "tx-stale")
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 1L)
        val payload = buildJsonObject { put("transactionId", "tx-stale"); put("ok", true) }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-stale-sale",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = "unknown-corr",
            )
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = null,
            )
        assertEquals(AckRouteOutcome.ORPHAN, outcome)
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)
    }

    @Test
    fun `rejects stale requestUuid ack from prior session generation`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440099"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 3L)
        val payload = buildJsonObject { put("requestUuid", requestUuid); put("ok", true) }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-stale-loyalty",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = null,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 4L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
            )
        assertEquals(AckRouteOutcome.ORPHAN, outcome)
        assertEquals(MachineOutboxStatus.IN_FLIGHT.name, persistence.allRows().single().status)
        assertTrue(!balanceCalled)
    }

    @Test
    fun `routes schemaHash to cells handler`() = runTest {
        var cellsCalled = false
        val payload = buildJsonObject { put("schemaHash", "abc123") }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-2",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = "cells-corr",
            )
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 1L,
                cellsHandler = { cellsCalled = true },
                loyaltyHandler = null,
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertTrue(cellsCalled)
    }

    @Test
    fun `routes subscription levels ack to loyalty handler`() = runTest {
        var receivedCorrelation: String? = null
        var receivedPayload: JsonObject? = null
        val payload =
            buildJsonObject {
                put(
                    "levels",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "level-1")
                                put("name", "Старт")
                                put("dailyVolumeMl", 12000)
                                put("priceKopecks", 49900)
                            },
                        )
                    },
                )
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-levels",
                sentAt = "2026-07-30T13:39:28.000Z",
                payload = payload,
                correlationId = "levels-corr",
            )

        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = { correlation, routedPayload ->
                    receivedCorrelation = correlation
                    receivedPayload = routedPayload
                },
            )

        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals("levels-corr", receivedCorrelation)
        assertEquals(payload, receivedPayload)
    }

    @Test
    fun `routes subscription payment ack to loyalty handler`() = runTest {
        var loyaltyCalled = false
        val payload =
            buildJsonObject {
                put("paymentId", "550e8400-e29b-41d4-a716-446655440001")
                put("status", "PENDING")
                put("amountKopecks", 49900)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-payment",
                sentAt = "2026-07-30T13:39:29.000Z",
                payload = payload,
                correlationId = "payment-corr",
            )

        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = { correlation, routedPayload ->
                    loyaltyCalled = correlation == "payment-corr" && routedPayload == payload
                },
            )

        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertTrue(loyaltyCalled)
    }

    @Test
    fun `routes correlationId outbox ack when generation matches`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "sale-1")
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 5L)
        val payload = buildJsonObject { put("ok", true) }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-3",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        router.routeAck(envelope, sessionGeneration = 5L, cellsHandler = null, loyaltyHandler = null)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
    }

    @Test
    fun `technician error invokes only technician handler`() = runTest {
        val loyaltyHandler = mockk<suspend (String?, String, String) -> Unit>(relaxed = true)
        val technicianHandler = mockk<suspend (String?, String, String) -> Unit>(relaxed = true)
        val envelope =
            MvpWsEnvelopeDto(
                type = "error",
                messageId = "err-1",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload =
                    buildJsonObject {
                        put("code", "KEY_REVOKED")
                        put("message", "Key revoked")
                        put("requestedScope", "service.menu")
                    },
                correlationId = "tech-corr-1",
            )
        val outcome =
            router.routeError(
                envelope = envelope,
                sessionGeneration = 1L,
                outboxErrorHandler = null,
                loyaltyErrorHandler = loyaltyHandler,
                technicianErrorHandler = technicianHandler,
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        coVerify(exactly = 1) { technicianHandler("tech-corr-1", "KEY_REVOKED", "Key revoked") }
        coVerify(exactly = 0) { loyaltyHandler(any(), any(), any()) }
    }

    @Test
    fun `orphan ack when no handler matches`() = runTest {
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-4",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = buildJsonObject { put("unknown", true) },
                correlationId = "orphan",
            )
        val outcome =
            router.routeAck(envelope, sessionGeneration = 1L, cellsHandler = null, loyaltyHandler = null)
        assertEquals(AckRouteOutcome.ORPHAN, outcome)
    }

    @Test
    fun `pour outbox ack by requestUuid marks acked and invokes balance handler`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440099"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 2L)
        val payload =
            buildJsonObject {
                put("requestUuid", requestUuid)
                put("dailyRemainingMl", 320)
                put("ok", true)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-pour-balance",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = null,
            )
        var balancePayload: JsonObject? = null
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balancePayload = it },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertEquals(payload, balancePayload)
    }

    @Test
    fun `pour outbox ack by correlationId marks acked and invokes balance handler`() = runTest {
        val requestUuid = "990e8400-e29b-41d4-a716-446655440088"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 7L)
        val payload =
            buildJsonObject {
                put("requestUuid", requestUuid)
                put("volumeAfterMl", 410)
                put("ok", true)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-pour-corr",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCallCount = 0
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 7L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCallCount += 1 },
            )
        router.routeAck(
            envelope = envelope,
            sessionGeneration = 7L,
            cellsHandler = null,
            loyaltyHandler = null,
            pourBalanceHandler = { balanceCallCount += 1 },
        )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertEquals(1, balanceCallCount)
    }

    @Test
    fun `paid complete outbox ack does not invoke pour balance handler`() = runTest {
        TestOutboxFixtures.enqueueTestPaidComplete(commerceOutboxStore, "tx-no-pour-balance")
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 1L)
        val payload = buildJsonObject { put("transactionId", "tx-no-pour-balance"); put("ok", true) }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-sale-only",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        router.routeAck(
            envelope = envelope,
            sessionGeneration = 1L,
            cellsHandler = null,
            loyaltyHandler = null,
            pourBalanceHandler = { balanceCalled = true },
        )
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertTrue(!balanceCalled)
    }

    @Test
    fun `orphan pour balance ack with dailyRemaining invokes balance handler`() = runTest {
        val payload =
            buildJsonObject {
                put("requestUuid", "orphan-pour-uuid")
                put("dailyRemainingMl", 180)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-orphan-balance",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = "unknown-message-id",
            )
        var balancePayload: JsonObject? = null
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 1L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balancePayload = it },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(payload, balancePayload)
    }

    @Test
    fun `rejects bare pour dedup ack by correlationId without persistence proof`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440001"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 2L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("deduplicated", true)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-bare-dedup-corr",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
                onUnprovenPourDedupAck = { entry -> outboxStore.rotateMessageIdForRetry(entry) },
            )
        assertEquals(AckRouteOutcome.UNPROVEN_POUR_DEDUP, outcome)
        val after = persistence.allRows().single()
        assertEquals(MachineOutboxStatus.PENDING.name, after.status)
        assertEquals(requestUuid, after.idempotencyKey)
        assertTrue(after.messageId != row.messageId)
        assertEquals(row.payloadJson, after.payloadJson)
        assertTrue(!balanceCalled)
    }

    @Test
    fun `rejects bare pour dedup ack by requestUuid without persistence proof`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440002"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 3L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("deduplicated", true)
                put("requestUuid", requestUuid)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-bare-dedup-uuid",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = null,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 3L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
                onUnprovenPourDedupAck = { entry -> outboxStore.rotateMessageIdForRetry(entry) },
            )
        assertEquals(AckRouteOutcome.UNPROVEN_POUR_DEDUP, outcome)
        val after = persistence.allRows().single()
        assertEquals(MachineOutboxStatus.PENDING.name, after.status)
        assertEquals(requestUuid, after.idempotencyKey)
        assertTrue(after.messageId != row.messageId)
        assertEquals(row.payloadJson, after.payloadJson)
        assertTrue(!balanceCalled)
    }

    @Test
    fun `accepts proven pour dedup ack by correlationId with pourId`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440003"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 4L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("deduplicated", true)
                put("requestUuid", requestUuid)
                put("pourId", "pour-db-123")
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-proven-dedup-corr",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 4L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertTrue(balanceCalled)
    }

    @Test
    fun `accepts proven pour dedup ack with balance field and outbox requestUuid`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440004"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 5L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("deduplicated", true)
                put("dailyRemainingMl", 280)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-proven-dedup-balance",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balancePayload: JsonObject? = null
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 5L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balancePayload = it },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertEquals(payload, balancePayload)
    }

    @Test
    fun `accepts normal pour success ack without deduplicated flag`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440005"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 6L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("requestUuid", requestUuid)
                put("dailyRemainingMl", 410)
                put("pourId", "pour-fresh-456")
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-normal-success",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 6L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertTrue(balanceCalled)
    }

    @Test
    fun `proven duplicate pour ack does not re-apply balance handler`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440006"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 8L)
        val payload =
            buildJsonObject {
                put("ok", true)
                put("deduplicated", true)
                put("requestUuid", requestUuid)
                put("volumeAfterMl", 390)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-proven-dedup-dup",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCallCount = 0
        router.routeAck(
            envelope = envelope,
            sessionGeneration = 8L,
            cellsHandler = null,
            loyaltyHandler = null,
            pourBalanceHandler = { balanceCallCount += 1 },
        )
        router.routeAck(
            envelope = envelope,
            sessionGeneration = 8L,
            cellsHandler = null,
            loyaltyHandler = null,
            pourBalanceHandler = { balanceCallCount += 1 },
        )
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertEquals(1, balanceCallCount)
    }

    @Test
    fun `plain water pour outbox ack does not invoke balance handler`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440010"
        TestOutboxFixtures.enqueueTestPlainWaterPour(
            pourOutboxStore = pourOutboxStore,
            requestUuid = requestUuid,
            plainWaterType = PlainWaterType.FILTERED,
        )
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 2L)
        val payload =
            buildJsonObject {
                put("requestUuid", requestUuid)
                put("dailyRemainingMl", 500)
                put("billingMode", "UNLIMITED")
                put("ok", true)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-plain-water",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        val outcome =
            router.routeAck(
                envelope = envelope,
                sessionGeneration = 2L,
                cellsHandler = null,
                loyaltyHandler = null,
                pourBalanceHandler = { balanceCalled = true },
            )
        assertEquals(AckRouteOutcome.HANDLED, outcome)
        assertEquals(MachineOutboxStatus.ACKED.name, persistence.allRows().single().status)
        assertTrue(!balanceCalled)
    }

    @Test
    fun `flavored pour outbox ack still invokes balance handler`() = runTest {
        val requestUuid = "880e8400-e29b-41d4-a716-446655440011"
        TestOutboxFixtures.enqueueTestPour(pourOutboxStore, requestUuid = requestUuid)
        val row = persistence.allRows().single()
        outboxStore.markInFlight(row, sessionGeneration = 2L)
        val payload =
            buildJsonObject {
                put("requestUuid", requestUuid)
                put("dailyRemainingMl", 320)
                put("ok", true)
            }
        val envelope =
            MvpWsEnvelopeDto(
                type = "ack",
                messageId = "ack-flavored-pour",
                sentAt = "2026-07-27T10:00:00.000Z",
                payload = payload,
                correlationId = row.messageId,
            )
        var balanceCalled = false
        router.routeAck(
            envelope = envelope,
            sessionGeneration = 2L,
            cellsHandler = null,
            loyaltyHandler = null,
            pourBalanceHandler = { balanceCalled = true },
        )
        assertTrue(balanceCalled)
    }
}
