package com.viwa.android.domain.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePhaseBusyTest {
    @Test
    fun `awaiting user blocks download and install`() {
        assertTrue(AppUpdatePhase.AwaitingUser.blocksDownloadOrInstall())
        assertTrue(AppUpdatePhase.AwaitingUser.isInstallUiBusy())
    }

    @Test
    fun `offered does not block download and install`() {
        assertFalse(AppUpdatePhase.Offered.blocksDownloadOrInstall())
        assertFalse(AppUpdatePhase.Offered.isInstallUiBusy())
    }
}
