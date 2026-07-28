package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class OtaManifestCanonicalSigningTest {
    private val fields =
        OtaManifestCanonicalSigning.CanonicalManifestFields(
            releaseId = "11111111-1111-1111-1111-111111111111",
            versionName = "1.2.3",
            versionCode = 42,
            channel = OtaReleaseChannel.STABLE,
            mandatory = true,
            sha256 = "abc123",
            fileSizeBytes = "999",
            signingCertSha256 = "certdeadbeef",
            changelog = "Fix bugs",
            revocationEpoch = 0,
        )

    @Test
    fun `canonical message matches backend pipe format`() {
        val message = OtaManifestCanonicalSigning.buildCanonicalMessage(fields)
        assertEquals(
            "app-release-manifest-v1|11111111-1111-1111-1111-111111111111|1.2.3|42|STABLE|1|abc123|999|certdeadbeef|Fix bugs|0",
            message.decodeToString(),
        )
    }

    @Test
    fun `null changelog becomes empty segment`() {
        val message =
            OtaManifestCanonicalSigning.buildCanonicalMessage(
                fields.copy(changelog = null),
            )
        assertEquals(
            "app-release-manifest-v1|11111111-1111-1111-1111-111111111111|1.2.3|42|STABLE|1|abc123|999|certdeadbeef||0",
            message.decodeToString(),
        )
    }
}
