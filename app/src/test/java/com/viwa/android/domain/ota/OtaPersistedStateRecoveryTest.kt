package com.viwa.android.domain.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaPersistedStateRecoveryTest {
    @Test
    fun `clears when installed version is at or above target`() {
        assertTrue(
            OtaPersistedStateRecovery.isStale(
                installedVersionCode = 217,
                targetVersionCode = 216,
                offerVersionCode = null,
                pendingApkVersionCode = null,
            ),
        )
    }

    @Test
    fun `clears when offer is downgrade`() {
        assertTrue(
            OtaPersistedStateRecovery.isStale(
                installedVersionCode = 217,
                targetVersionCode = 218,
                offerVersionCode = 216,
                pendingApkVersionCode = null,
            ),
        )
    }

    @Test
    fun `clears when pending apk is downgrade`() {
        assertTrue(
            OtaPersistedStateRecovery.isStale(
                installedVersionCode = 217,
                targetVersionCode = null,
                offerVersionCode = null,
                pendingApkVersionCode = 216,
            ),
        )
    }

    @Test
    fun `keeps state when installed is below target`() {
        assertFalse(
            OtaPersistedStateRecovery.isStale(
                installedVersionCode = 216,
                targetVersionCode = 217,
                offerVersionCode = 217,
                pendingApkVersionCode = 217,
            ),
        )
    }

    @Test
    fun `awaiting user with offer recovers to offered`() {
        val offer = sampleOffer()
        val plan =
            OtaPersistedStateRecovery.planProcessDeathRecovery(
                phase = AppUpdatePhase.AwaitingUser,
                offer = offer,
                persistedPendingPath = "/data/apk.apk",
                safePendingApkPath = "/data/apk.apk",
            )

        assertFalse(plan.clearAllState)
        assertEquals(AppUpdatePhase.Offered, plan.targetPhase)
        assertEquals("/data/apk.apk", plan.pendingApkPath)
        assertFalse(plan.shouldDeletePendingApk)
    }

    @Test
    fun `awaiting user without offer recovers to idle`() {
        val plan =
            OtaPersistedStateRecovery.planProcessDeathRecovery(
                phase = AppUpdatePhase.AwaitingUser,
                offer = null,
                persistedPendingPath = "/data/apk.apk",
                safePendingApkPath = "/data/apk.apk",
            )

        assertTrue(plan.clearAllState)
        assertEquals(AppUpdatePhase.Idle, plan.targetPhase)
        assertTrue(plan.shouldDeletePendingApk)
    }

    @Test
    fun `downloading clears partial and recovers to offered`() {
        val offer = sampleOffer()
        val plan =
            OtaPersistedStateRecovery.planProcessDeathRecovery(
                phase = AppUpdatePhase.Downloading,
                offer = offer,
                persistedPendingPath = "/data/apk.part",
                safePendingApkPath = "/data/apk.apk",
            )

        assertEquals(AppUpdatePhase.Offered, plan.targetPhase)
        assertNull(plan.pendingApkPath)
        assertTrue(plan.shouldDeletePendingApk)
    }

    @Test
    fun `unsafe pending apk is dropped on install interrupt recovery`() {
        val offer = sampleOffer()
        val plan =
            OtaPersistedStateRecovery.planProcessDeathRecovery(
                phase = AppUpdatePhase.Installing,
                offer = offer,
                persistedPendingPath = "/tmp/unsafe.apk",
                safePendingApkPath = null,
            )

        assertEquals(AppUpdatePhase.Offered, plan.targetPhase)
        assertNull(plan.pendingApkPath)
        assertTrue(plan.shouldDeletePendingApk)
    }

    private fun sampleOffer(): OtaUpdateOffer =
        OtaUpdateOffer(
            releaseId = "11111111-1111-1111-1111-111111111111",
            versionName = "2.0.0",
            versionCode = 200,
            channel = com.viwa.android.data.remote.ota.OtaReleaseChannel.STABLE,
            mandatory = false,
            sha256 = "a".repeat(64),
            fileSizeBytes = 1024,
            signingCertSha256 = "b".repeat(64),
            changelog = "",
            manifestKeyId = "k1",
            manifestSignature = "sig",
            downloadUrl = "https://example.com/apk",
            downloadExpiresAt = "2030-01-01T00:00:00Z",
        )
}
