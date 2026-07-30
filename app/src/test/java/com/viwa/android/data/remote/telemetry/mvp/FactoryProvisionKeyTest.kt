package com.viwa.android.data.remote.telemetry.mvp

import org.junit.Assert.assertEquals
import org.junit.Test

class FactoryProvisionKeyTest {
    @Test
    fun `reveal returns factory provision key matching server`() {
        // when
        val revealed = FactoryProvisionKey.reveal()
        // then
        assertEquals("VFP-K7mN2pQx9Rw4HsYc6TdL8BvA3fG", revealed)
    }
}
