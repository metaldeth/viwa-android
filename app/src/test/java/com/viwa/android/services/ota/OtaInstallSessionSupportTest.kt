package com.viwa.android.services.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaInstallSessionSupportTest {
    @Test
    fun `has space when usable exceeds apk plus margin`() {
        assertTrue(OtaInstallSessionSupport.hasSpaceForStaging(usableSpaceBytes = 50_000_000L, apkBytes = 10_000_000L))
    }

    @Test
    fun `no space when usable is below apk plus margin`() {
        assertFalse(OtaInstallSessionSupport.hasSpaceForStaging(usableSpaceBytes = 10_000_000L, apkBytes = 10_000_000L))
    }
}
