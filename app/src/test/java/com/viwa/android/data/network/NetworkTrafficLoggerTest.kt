package com.viwa.android.data.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrafficLoggerTest {
    @Test
    fun `should preserve insertion order in snapshot`() {
        val logger = NetworkTrafficLogger()
        logger.log(NetworkTrafficChannel.WS, NetworkTrafficDirection.OUT, "first")
        logger.log(NetworkTrafficChannel.WS, NetworkTrafficDirection.IN, "second")
        logger.log(NetworkTrafficChannel.HTTP, NetworkTrafficDirection.SYSTEM, "third")

        val entries = logger.entries.value
        assertEquals(listOf("first", "second", "third"), entries.map { it.summary })
        assertEquals(listOf(0, 1, 2), entries.map { it.id })
    }

    @Test
    fun `should drop oldest entries when exceeding MAX_ENTRIES`() {
        val logger = NetworkTrafficLogger()
        repeat(NetworkTrafficLogger.MAX_ENTRIES + 5) { index ->
            logger.log(NetworkTrafficChannel.WS, NetworkTrafficDirection.OUT, "line-$index")
        }

        val entries = logger.entries.value
        assertEquals(NetworkTrafficLogger.MAX_ENTRIES, entries.size)
        assertEquals("line-5", entries.first().summary)
        assertEquals("line-${NetworkTrafficLogger.MAX_ENTRIES + 4}", entries.last().summary)
    }

    @Test
    fun `should handle concurrent writes without losing newest tail`() {
        val logger = NetworkTrafficLogger()
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)

        repeat(threads) { threadIndex ->
            pool.execute {
                startGate.await()
                repeat(perThread) { offset ->
                    val id = threadIndex * perThread + offset
                    logger.log(NetworkTrafficChannel.WS, NetworkTrafficDirection.OUT, "t$id")
                }
                doneGate.countDown()
            }
        }

        startGate.countDown()
        assertTrue(doneGate.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        val entries = runBlocking { logger.entries.first { it.size == NetworkTrafficLogger.MAX_ENTRIES } }
        assertEquals(NetworkTrafficLogger.MAX_ENTRIES, entries.size)
        assertTrue(entries.last().summary.startsWith("t"))
    }
}
