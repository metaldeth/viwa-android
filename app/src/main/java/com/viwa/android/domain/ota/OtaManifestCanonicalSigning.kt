package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel

/** Must match `viwa-telemetry/.../app-updates-canonical.util.ts`. */
object OtaManifestCanonicalSigning {
    data class CanonicalManifestFields(
        val releaseId: String,
        val versionName: String,
        val versionCode: Int,
        val channel: OtaReleaseChannel,
        val mandatory: Boolean,
        val sha256: String,
        val fileSizeBytes: String,
        val signingCertSha256: String,
        val changelog: String?,
        val revocationEpoch: Int,
    )

    fun buildCanonicalMessage(fields: CanonicalManifestFields): ByteArray {
        val parts =
            listOf(
                MANIFEST_DOMAIN,
                fields.releaseId,
                fields.versionName,
                fields.versionCode.toString(),
                fields.channel.name,
                if (fields.mandatory) "1" else "0",
                fields.sha256,
                fields.fileSizeBytes,
                fields.signingCertSha256,
                fields.changelog.orEmpty(),
                fields.revocationEpoch.toString(),
            )
        return parts.joinToString("|").encodeToByteArray()
    }

    const val MANIFEST_DOMAIN = "app-release-manifest-v1"
}
