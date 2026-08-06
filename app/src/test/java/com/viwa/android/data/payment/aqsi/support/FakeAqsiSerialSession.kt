package com.viwa.android.data.payment.aqsi.support

import com.viwa.android.data.payment.aqsi.serial.AqsiSerialLink
import java.io.IOException

class FakeAqsiSerialSession(
    private val readQueue: ArrayDeque<ByteArray> = ArrayDeque(),
    var failOnWrite: Boolean = false,
    val writes: MutableList<ByteArray> = mutableListOf(),
) : AqsiSerialLink {
    override val isOpen: Boolean = true

    override fun read(timeoutMs: Int): ByteArray? =
        if (readQueue.isEmpty()) null else readQueue.removeFirst()

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (failOnWrite) throw IOException("USB write failed")
        writes += data.copyOf()
    }

    override fun close() = Unit
}
