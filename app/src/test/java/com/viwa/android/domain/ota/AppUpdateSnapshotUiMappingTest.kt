package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateSnapshotUiMappingTest {
    @Test
    fun `available update cleared when offer is null`() {
        val offer =
            OtaUpdateOffer(
                releaseId = "11111111-1111-1111-1111-111111111111",
                versionName = "2.0.0",
                versionCode = 200,
                channel = OtaReleaseChannel.STABLE,
                mandatory = false,
                sha256 = "a".repeat(64),
                fileSizeBytes = 1024,
                signingCertSha256 = "b".repeat(64),
                changelog = "test",
                manifestKeyId = "k1",
                manifestSignature = "sig",
                downloadUrl = "https://example.com/apk",
                downloadExpiresAt = "2030-01-01T00:00:00Z",
            )
        val withOffer = AppUpdateCoordinatorSnapshot(phase = AppUpdatePhase.Offered, offer = offer)
        assertEquals(200, withOffer.availableUpdateForUi()?.versionCode)

        val afterSuccess = withOffer.copy(phase = AppUpdatePhase.Success, offer = null)
        assertNull(afterSuccess.availableUpdateForUi())

        val idle = AppUpdateCoordinatorSnapshot(phase = AppUpdatePhase.Idle, offer = null)
        assertNull(idle.availableUpdateForUi())
    }
}
