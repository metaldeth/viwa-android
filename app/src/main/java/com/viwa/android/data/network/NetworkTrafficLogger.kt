package com.viwa.android.data.network

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkTrafficChannel { WS, HTTP }

enum class NetworkTrafficDirection { OUT, IN, SYSTEM }

data class NetworkTrafficEntry(
    val id: Int,
    val timestampMs: Long,
    val channel: NetworkTrafficChannel,
    val direction: NetworkTrafficDirection,
    val summary: String,
    val payload: String,
)

/**
 * Thread-safe bounded ring buffer preserving insertion order for [StateFlow] consumers.
 */
internal class NetworkTrafficRingBuffer(
    private val capacity: Int,
) {
    private val buffer = arrayOfNulls<NetworkTrafficEntry>(capacity)
    private var writeIndex = 0
    private var size = 0

    @Synchronized
    fun add(entry: NetworkTrafficEntry) {
        buffer[writeIndex] = entry
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) {
            size++
        }
    }

    @Synchronized
    fun snapshot(): List<NetworkTrafficEntry> {
        if (size == 0) return emptyList()
        val start = if (size < capacity) 0 else writeIndex
        return buildList(size) {
            repeat(size) { offset ->
                buffer[(start + offset) % capacity]?.let(::add)
            }
        }
    }

    @Synchronized
    fun clear() {
        buffer.fill(null)
        writeIndex = 0
        size = 0
    }
}

@Singleton
class NetworkTrafficLogger
@Inject
constructor() {
    private val maxEntries = MAX_ENTRIES
    private val counter = AtomicInteger(0)
    private val ring = NetworkTrafficRingBuffer(maxEntries)

    private val _entries = MutableStateFlow<List<NetworkTrafficEntry>>(emptyList())
    val entries: StateFlow<List<NetworkTrafficEntry>> = _entries.asStateFlow()

    fun log(
        channel: NetworkTrafficChannel,
        direction: NetworkTrafficDirection,
        summary: String,
        payload: String = summary,
    ) {
        val line =
            NetworkTrafficEntry(
                id = counter.getAndIncrement(),
                timestampMs = System.currentTimeMillis(),
                channel = channel,
                direction = direction,
                summary = summary,
                payload = payload,
            )
        synchronized(this) {
            ring.add(line)
            _entries.value = ring.snapshot()
        }
    }

    fun clear() {
        synchronized(this) {
            ring.clear()
            _entries.value = emptyList()
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
