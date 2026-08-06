package com.viwa.android.hardware.controller

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.outbox.FakeMachineOutboxPersistence
import com.viwa.android.data.local.outbox.MachineOutboxStore
import com.viwa.android.data.local.outbox.PendingSalesOutboxMigrator
import com.viwa.android.data.local.outbox.WaterUsageOutboxStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxDrainCoordinator
import com.viwa.android.data.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViwaWaterCounterServiceTest {
    private lateinit var hardware: ControllerHardwareManager
    private lateinit var configRepository: FakeConfigRepository
    private lateinit var waterUsageOutboxStore: WaterUsageOutboxStore
    private lateinit var drainCoordinator: MachineOutboxDrainCoordinator
    private lateinit var responses: MutableSharedFlow<ControllerResponseEvent>
    private lateinit var service: ViwaWaterCounterService
    private val readValues = AtomicInteger(0)
    private val responseQueue = ArrayDeque<Int>()

    @Before
    fun setUp() {
        hardware = mockk(relaxed = true)
        configRepository = FakeConfigRepository()
        val persistence = FakeMachineOutboxPersistence()
        val outboxStore =
            MachineOutboxStore(
                persistence = persistence,
                configRepository = configRepository,
                migrator =
                    PendingSalesOutboxMigrator(
                        persistence = persistence,
                        configRepository = configRepository,
                    ),
            )
        waterUsageOutboxStore = WaterUsageOutboxStore(outboxStore)
        drainCoordinator = mockk(relaxed = true)
        responses = MutableSharedFlow(extraBufferCapacity = 32)
        readValues.set(0)
        responseQueue.clear()
        responseQueue.add(120)
        coEvery { hardware.incomingResponses } returns responses
        coEvery { hardware.sendCommand(any(), any()) } coAnswers {
            val command = firstArg<RequestCommand>()
            if (command == RequestCommand.ReadWaterCounter) {
                delay(5)
                val ml = responseQueue.removeFirstOrNull() ?: 0
                readValues.incrementAndGet()
                responses.emit(waterCounterAnswer(ml))
            }
        }
        service =
            ViwaWaterCounterService(
                hardware = hardware,
                configRepository = configRepository,
                waterUsageOutboxStore = waterUsageOutboxStore,
                outboxDrainCoordinator = drainCoordinator,
            )
    }

    @Test
    fun `readAccumulateAndReset adds delta once and enqueues absolute water usage report`() = runTest {
        val result = service.readAccumulateAndResetController()

        assertEquals(120, result.deltaMl)
        assertEquals(120.0, result.lifetimeTotalMl, 0.001)
        assertTrue(result.controllerResetSent)
        assertEquals("120.0", configRepository.get(JsonStoreKeys.WATER_USAGE_ML))
        coVerify(exactly = 1) { hardware.sendCommand(RequestCommand.ReadWaterCounter, any()) }
        coVerify(exactly = 1) { hardware.sendCommand(RequestCommand.ResetWaterCounter, any()) }
        coVerify(exactly = 1) { drainCoordinator.onEnqueue() }
    }

    @Test
    fun `zero hardware read still resets controller without lifetime increment`() = runTest {
        responseQueue.clear()
        responseQueue.add(0)

        val result = service.readAccumulateAndResetController()

        assertEquals(0, result.deltaMl)
        assertEquals(0.0, result.lifetimeTotalMl, 0.001)
        assertTrue(result.controllerResetSent)
        assertNull(configRepository.get(JsonStoreKeys.WATER_USAGE_ML))
        coVerify(exactly = 0) { drainCoordinator.onEnqueue() }
        coVerify(exactly = 1) { hardware.sendCommand(RequestCommand.ResetWaterCounter, any()) }
    }

    @Test
    fun `lifetime total is never cleared when operator reset reads zero delta`() = runTest {
        configRepository.set(JsonStoreKeys.WATER_USAGE_ML, "500.0")
        responseQueue.clear()
        responseQueue.add(0)

        val result = service.resetControllerAfterAccumulating()

        assertEquals(0, result.deltaMl)
        assertEquals(500.0, result.lifetimeTotalMl, 0.001)
        assertTrue(result.controllerResetSent)
        assertEquals("500.0", configRepository.get(JsonStoreKeys.WATER_USAGE_ML))
        coVerify(exactly = 1) { hardware.sendCommand(RequestCommand.ResetWaterCounter, any()) }
    }

    @Test
    fun `hold pour flushes pre-existing controller volume then reports session delta`() = runTest {
        responseQueue.clear()
        responseQueue.addAll(listOf(40, 200))

        service.beginHoldPourSession()
        assertEquals(40.0, service.getAccumulatedWaterUsageMl(), 0.001)

        val delta = service.endHoldPourSessionAndReset()

        assertEquals(200, delta)
        assertEquals(240.0, service.getAccumulatedWaterUsageMl(), 0.001)
    }

    @Test
    fun `cancelled hold pour volume is flushed on next operator read`() = runTest {
        responseQueue.clear()
        responseQueue.addAll(listOf(0, 75, 75))

        service.beginHoldPourSession()
        service.cancelHoldPourSession()

        val result = service.readAccumulateAndResetController()

        assertEquals(75, result.deltaMl)
        assertEquals(75.0, result.lifetimeTotalMl, 0.001)
        coVerify(exactly = 1) { hardware.sendCommand(RequestCommand.ResetWaterCounter, any()) }
    }

    @Test
    fun `concurrent reads serialize and do not cross-match answers`() = runTest {
        responseQueue.clear()
        responseQueue.addAll(listOf(100, 200, 300))

        val first = async { service.readAccumulateAndResetController() }
        val second = async { service.readAccumulateAndResetController() }
        val third = async { service.readAccumulateAndResetController() }

        val results = listOf(first.await(), second.await(), third.await())
        val deltas = results.map { it.deltaMl }.sorted()

        assertEquals(listOf(100, 200, 300), deltas)
        assertEquals(600.0, service.getAccumulatedWaterUsageMl(), 0.001)
        assertEquals(3, readValues.get())
        coVerify(exactly = 3) { hardware.sendCommand(RequestCommand.ReadWaterCounter, any()) }
        coVerify(exactly = 3) { hardware.sendCommand(RequestCommand.ResetWaterCounter, any()) }
        coVerify(exactly = 3) { drainCoordinator.onEnqueue() }
    }

    private fun waterCounterAnswer(ml: Int): ControllerResponseEvent {
        val hi = (ml shr 8) and 0xff
        val lo = ml and 0xff
        return ControllerResponseEvent(
            response = ResponseCommand.WaterCounterAnswer,
            payload = byteArrayOf(hi.toByte(), lo.toByte()),
        )
    }

    private class FakeConfigRepository : ConfigRepository {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun set(
            key: String,
            value: String,
        ) {
            store[key] = value
        }

        override suspend fun delete(key: String) {
            store.remove(key)
        }

        override suspend fun getJson(key: String): String? = store[key]

        override suspend fun setJson(key: String, json: String) {
            store[key] = json
        }
    }
}
