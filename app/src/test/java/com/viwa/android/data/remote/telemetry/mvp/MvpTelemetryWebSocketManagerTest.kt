package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.network.NetworkTrafficLogger
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MvpTelemetryWebSocketManagerTest {
    private lateinit var trafficLogger: NetworkTrafficLogger
    private lateinit var manager: MvpTelemetryWebSocketManager
    private lateinit var cellsHandler: MvpTelemetryCellsSyncHandler
    private lateinit var loyaltyHandler: MvpTelemetryLoyaltySyncHandler

    private val helloJson =
        """
        {
          "type": "hello",
          "messageId": "msg-hello-1",
          "sentAt": "2026-07-27T12:00:00Z",
          "payload": {
            "serialNumber": "VIWA-000001",
            "heartbeatIntervalSeconds": 10
          }
        }
        """.trimIndent()

    @Before
    fun setUp() {
        trafficLogger = NetworkTrafficLogger()
        cellsHandler = mockk(relaxed = true)
        loyaltyHandler = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) {
            manager.disconnect()
        }
    }

    @Test
    fun `should drop stale hello and not set ONLINE`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            manager.cellsSyncHandler = cellsHandler
            val activeClient = manager.createDetachedClientForTests(sessionGeneration = 2L)
            manager.bindActiveSessionForTests(activeClient, sessionGeneration = 2L)
            val staleClient = manager.createDetachedClientForTests(sessionGeneration = 1L)

            // when
            manager.deliverInboundForTests(1L, staleClient, helloJson)

            // then
            assertFalse(manager.connectionState.value is ConnectionState.Connected)
            advanceUntilIdle()
            coVerify(exactly = 0) { cellsHandler.onWebSocketHello(any<MvpHelloPayloadDto>()) }
            assertTrue(
                trafficLogger.entries.value.any { it.summary.contains("drop stale inbound") },
            )
            manager.disconnect()
        }

    @Test
    fun `should accept hello from active session and transition to Connected`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            manager.cellsSyncHandler = cellsHandler
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)

            // when
            manager.deliverInboundForTests(1L, client, helloJson)
            advanceTimeBy(1)
            manager.disconnect()
            advanceUntilIdle()

            // then
            assertEquals(TelemetryConnectionPhase.Idle, manager.fsmPhase())
            coVerify(exactly = 1) { cellsHandler.onWebSocketHello(any<MvpHelloPayloadDto>()) }
        }

    @Test
    fun `should set Connected before disconnect on hello`() =
        runTest {
            manager = createWsManagerForTests(trafficLogger)
            manager.cellsSyncHandler = cellsHandler
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            manager.deliverInboundForTests(1L, client, helloJson)
            assertEquals(ConnectionState.Connected, manager.connectionState.value)
            assertEquals(TelemetryConnectionPhase.Active, manager.fsmPhase())
            manager.disconnect()
        }

    @Test
    fun `should drop stale ack for loyalty handler`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            manager.loyaltySyncHandler = loyaltyHandler
            val activeClient = manager.createDetachedClientForTests(sessionGeneration = 2L)
            manager.bindActiveSessionForTests(activeClient, sessionGeneration = 2L)
            val staleClient = manager.createDetachedClientForTests(sessionGeneration = 1L)
            val ackJson =
                """
                {
                  "type": "ack",
                  "messageId": "msg-ack-1",
                  "sentAt": "2026-07-27T12:00:01Z",
                  "correlationId": "corr-1",
                  "payload": { "dailyRemainingMl": 100 }
                }
                """.trimIndent()

            // when
            manager.deliverInboundForTests(1L, staleClient, ackJson)
            advanceUntilIdle()

            // then
            coVerify(exactly = 0) { loyaltyHandler.onLoyaltyAck(any(), any()) }
            manager.disconnect()
        }

    @Test
    fun `hello timeout transitions to Backoff when active session`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            manager.startHelloTimeoutForTests(client, sessionGeneration = 1L)

            // when
            advanceTimeBy(15_000L + 100)
            advanceUntilIdle()

            // then
            assertTrue(
                manager.fsmTransitions().any {
                    it.to == TelemetryConnectionPhase.Backoff && it.reason == "hello timeout"
                },
            )
            manager.disconnect()
        }

    @Test
    fun `heartbeat watchdog closes stale session on ack timeout`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            manager.deliverInboundForTests(1L, client, helloJson)
            manager.startHeartbeatWatchdogForTests(client, sessionGeneration = 1L)

            // when
            advanceTimeBy(26_000)
            advanceUntilIdle()

            // then
            assertTrue(
                manager.fsmTransitions().any { it.reason == "heartbeat ack timeout" },
            )
            manager.disconnect()
        }

    @Test
    fun `notifyNetworkDegraded exposes signal without forcing Connected to drop`() =
        runTest {
            // given
            manager = createWsManagerForTests(trafficLogger)
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            manager.deliverInboundForTests(1L, client, helloJson)
            assertEquals(ConnectionState.Connected, manager.connectionState.value)

            // when
            manager.notifyNetworkDegraded()

            // then
            assertEquals(ConnectionState.Connected, manager.connectionState.value)
            assertTrue(
                trafficLogger.entries.value.any { it.summary.contains("network degraded") },
            )
            manager.disconnect()
        }

    @Test
    fun `repeated network validated does not reset backoff connect lifecycle`() =
        runTest {
            manager = createWsManagerForTests(trafficLogger)
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            // Simulate mid-backoff reconnect loop (connectJob not easily started without real socket)
            // Use internal phase assignment via hello timeout → backoff path
            manager.startHelloTimeoutForTests(client, sessionGeneration = 1L)
            advanceTimeBy(15_000L + 100)
            advanceUntilIdle()
            assertEquals(TelemetryConnectionPhase.Backoff, manager.fsmPhase())

            manager.notifyNetworkValidated()
            manager.notifyNetworkValidated()
            assertTrue(manager.shouldInitiateConnectOnNetworkValidated().not())
            manager.disconnect()
        }

    @Test
    fun `should initiate connect from idle on network validated`() =
        runTest {
            manager = createWsManagerForTests(trafficLogger)
            assertEquals(TelemetryConnectionPhase.Idle, manager.fsmPhase())
            manager.notifyNetworkValidated()
            assertTrue(manager.shouldInitiateConnectOnNetworkValidated())
            manager.disconnect()
        }

    @Test
    fun `should not initiate connect when active session online`() =
        runTest {
            manager = createWsManagerForTests(trafficLogger)
            manager.cellsSyncHandler = cellsHandler
            val client = manager.createDetachedClientForTests(sessionGeneration = 1L)
            manager.bindActiveSessionForTests(client, sessionGeneration = 1L)
            manager.deliverInboundForTests(1L, client, helloJson)
            manager.notifyNetworkValidated()
            assertFalse(manager.shouldInitiateConnectOnNetworkValidated())
            manager.disconnect()
        }

    @Test
    fun `missing JWT keeps retrying without network transition`() =
        runTest {
            manager = createWsManagerForTests(trafficLogger)
            var tokenRequests = 0
            var authInvalidations = 0

            manager.connect(
                wsUrl = "ws://127.0.0.1:1",
                tokenProvider = {
                    tokenRequests += 1
                    null
                },
                onAuthFailure = { authInvalidations += 1 },
            )

            advanceTimeBy(120_000L)

            assertTrue(tokenRequests >= 2)
            assertEquals(tokenRequests, authInvalidations)
            assertTrue(manager.hasActiveConnectLifecycle())
            manager.disconnect()
        }
}
