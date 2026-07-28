package com.viwa.android.data.remote.telemetry.mvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsSessionFenceTest {
    @Test
    fun `should accept only matching client and generation`() {
        // given
        val clientA = Any()
        val clientB = Any()

        // then
        assertTrue(WsSessionFence.accept(clientA, 1L, clientA, 1L))
        assertFalse(WsSessionFence.accept(clientB, 1L, clientA, 1L))
        assertFalse(WsSessionFence.accept(clientA, 2L, clientA, 1L))
    }

    @Test
    fun `drop reason includes generation mismatch details`() {
        // when
        val reason = WsSessionFence.dropReason("hello", 1L, 2L, sameClient = true)

        // then
        assertTrue(reason.contains("hello"))
        assertTrue(reason.contains("gen=1"))
        assertTrue(reason.contains("active=2"))
    }
}
