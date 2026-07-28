package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaSignedManifestDto
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.remote.telemetry.mvp.EpochMillisClock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import timber.log.Timber

sealed class OtaManifestVerificationError(message: String) : Exception(message) {
    class UnknownSigningKey(keyId: String) : OtaManifestVerificationError("Unknown OTA signing key: $keyId")

    class InvalidSignature : OtaManifestVerificationError("Invalid manifest signature")

    class ExpiredDownloadUrl : OtaManifestVerificationError("Download URL expired")

    class InvalidManifestField(field: String) : OtaManifestVerificationError("Invalid manifest field: $field")

    class MissingRevocationEpoch : OtaManifestVerificationError("Missing manifest revocationEpoch")

    class RevokedSigningKey : OtaManifestVerificationError("OTA signing key revoked")

    class StaleRevocationEpoch : OtaManifestVerificationError("Manifest revocation epoch rolled back")
}

@Singleton
class OtaManifestVerifier
@Inject
constructor(
    private val signingKeysStore: OtaSigningKeysStore,
    private val signingPolicyStore: OtaSigningPolicyStore,
    private val clock: EpochMillisClock,
) {
    fun verifyManifest(manifest: OtaSignedManifestDto): Result<Unit> =
        runCatching {
            validateFields(manifest)
            verifyRevocationEpoch(manifest)
            verifyDownloadNotExpired(manifest.downloadExpiresAt)
            if (!verifySignature(manifest)) {
                throw OtaManifestVerificationError.InvalidSignature()
            }
        }

    fun verifyDownloadNotExpired(downloadExpiresAt: String) {
        val expiresMs =
            runCatching { TelemetryIsoTimestamps.parseUtcToEpochMillis(downloadExpiresAt) }
                .getOrElse { throw OtaManifestVerificationError.InvalidManifestField("downloadExpiresAt") }
        if (clock.epochMillis() >= expiresMs) {
            throw OtaManifestVerificationError.ExpiredDownloadUrl()
        }
    }

    fun verifySignature(manifest: OtaSignedManifestDto): Boolean {
        val revocationEpoch =
            manifest.revocationEpoch
                ?: return false
        val keys = signingKeysStore.findKeys(manifest.manifestKeyId)
        if (keys.isEmpty()) return false
        val fields = manifest.toCanonicalFields(revocationEpoch)
        val message = OtaManifestCanonicalSigning.buildCanonicalMessage(fields)
        val signature =
            runCatching {
                Base64.getUrlDecoder().decode(manifest.manifestSignature)
            }.getOrElse {
                return false
            }
        for (key in keys) {
            if (revocationEpoch < key.revocationEpoch) continue
            if (verifyEd25519(key.publicKeyPem, message, signature)) {
                return true
            }
        }
        return false
    }

    private fun verifyRevocationEpoch(manifest: OtaSignedManifestDto) {
        val revocationEpoch =
            manifest.revocationEpoch
                ?: throw OtaManifestVerificationError.MissingRevocationEpoch()
        if (revocationEpoch < signingPolicyStore.getTrustedRevocationEpoch()) {
            throw OtaManifestVerificationError.StaleRevocationEpoch()
        }
        val keys = signingKeysStore.findKeys(manifest.manifestKeyId)
        if (keys.isEmpty()) {
            throw OtaManifestVerificationError.UnknownSigningKey(manifest.manifestKeyId)
        }
        if (keys.none { revocationEpoch >= it.revocationEpoch }) {
            throw OtaManifestVerificationError.RevokedSigningKey()
        }
    }

    private fun validateFields(manifest: OtaSignedManifestDto) {
        if (manifest.releaseId.isBlank()) throw OtaManifestVerificationError.InvalidManifestField("releaseId")
        if (manifest.sha256.isBlank()) throw OtaManifestVerificationError.InvalidManifestField("sha256")
        if (manifest.signingCertSha256.isBlank()) {
            throw OtaManifestVerificationError.InvalidManifestField("signingCertSha256")
        }
        val size =
            manifest.fileSizeBytes.toLongOrNull()
                ?: throw OtaManifestVerificationError.InvalidManifestField("fileSizeBytes")
        if (size <= 0L || size > OtaConstants.MAX_APK_BYTES) {
            throw OtaManifestVerificationError.InvalidManifestField("fileSizeBytes")
        }
        if (manifest.versionCode <= 0) throw OtaManifestVerificationError.InvalidManifestField("versionCode")
        val revocationEpoch = manifest.revocationEpoch
        if (revocationEpoch == null || revocationEpoch < 0) {
            throw OtaManifestVerificationError.MissingRevocationEpoch()
        }
    }

    private fun verifyEd25519(publicKeyPem: String, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val spki = SubjectPublicKeyInfo.getInstance(parsePemBody(publicKeyPem))
            val publicKey = Ed25519PublicKeyParameters(spki.publicKeyData.bytes, 0)
            val signer = Ed25519Signer()
            signer.init(false, publicKey)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        }.getOrElse {
            Timber.tag(TAG).w(it, "Ed25519 verify failed")
            false
        }

    private fun parsePemBody(pem: String): ByteArray {
        val body =
            pem
                .lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
        return Base64.getDecoder().decode(body)
    }

    companion object {
        private const val TAG = "OtaManifestVerifier"

        fun OtaSignedManifestDto.toCanonicalFields(revocationEpoch: Int): OtaManifestCanonicalSigning.CanonicalManifestFields =
            OtaManifestCanonicalSigning.CanonicalManifestFields(
                releaseId = releaseId,
                versionName = versionName,
                versionCode = versionCode,
                channel = channel,
                mandatory = mandatory,
                sha256 = sha256.lowercase(),
                fileSizeBytes = fileSizeBytes,
                signingCertSha256 = signingCertSha256.lowercase(),
                changelog = changelog,
                revocationEpoch = revocationEpoch,
            )
    }
}

object OtaConstants {
    const val MAX_APK_BYTES = 209_715_200L
    const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    const val TEMP_APK_NAME = "ota-update-pending.apk"
}
