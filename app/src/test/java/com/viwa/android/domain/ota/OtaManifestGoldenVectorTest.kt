package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-repo golden vector — must match `viwa-telemetry/apps/api/test/fixtures/ota/manifest-golden-vector.json`. */
class OtaManifestGoldenVectorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `shared golden fixture canonical message and signature verify on Android`() {
        val fixture =
            json.decodeFromString<GoldenVectorFixture>(
                requireNotNull(
                    javaClass.classLoader?.getResourceAsStream("ota/manifest-golden-vector.json"),
                ) {
                    "Missing ota/manifest-golden-vector.json"
                }.bufferedReader().readText(),
            )

        val fields =
            OtaManifestCanonicalSigning.CanonicalManifestFields(
                releaseId = fixture.manifestFields.releaseId,
                versionName = fixture.manifestFields.versionName,
                versionCode = fixture.manifestFields.versionCode,
                channel = OtaReleaseChannel.valueOf(fixture.manifestFields.channel),
                mandatory = fixture.manifestFields.mandatory,
                sha256 = fixture.manifestFields.sha256,
                fileSizeBytes = fixture.manifestFields.fileSizeBytes,
                signingCertSha256 = fixture.manifestFields.signingCertSha256,
                changelog = fixture.manifestFields.changelog,
                revocationEpoch = fixture.manifestFields.revocationEpoch,
            )
        val message = OtaManifestCanonicalSigning.buildCanonicalMessage(fields)
        assertEquals(fixture.canonicalMessage, message.decodeToString())

        val keysStore = OtaSigningKeysStore()
        keysStore.updateFromHello(
            listOf(
                OfflineSigningPublicKeyDto(
                    keyId = fixture.keyId,
                    publicKeyPem = fixture.publicKeyPem,
                    revocationEpoch = fixture.manifestFields.revocationEpoch,
                ),
            ),
        )
        val signatureBytes = Base64.getUrlDecoder().decode(fixture.manifestSignature)
        assertTrue(verifyEd25519(fixture.publicKeyPem, message, signatureBytes))
    }

    private fun verifyEd25519(publicKeyPem: String, message: ByteArray, signature: ByteArray): Boolean {
        val body =
            publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(body)
        val spki = SubjectPublicKeyInfo.getInstance(der)
        val publicKey = Ed25519PublicKeyParameters(spki.publicKeyData.bytes, 0)
        val signer = Ed25519Signer()
        signer.init(false, publicKey)
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    @Serializable
    private data class GoldenVectorFixture(
        @SerialName("manifestFields") val manifestFields: GoldenManifestFields,
        @SerialName("canonicalMessage") val canonicalMessage: String,
        @SerialName("keyId") val keyId: String,
        @SerialName("publicKeyPem") val publicKeyPem: String,
        @SerialName("manifestSignature") val manifestSignature: String,
    )

    @Serializable
    private data class GoldenManifestFields(
        @SerialName("releaseId") val releaseId: String,
        @SerialName("versionName") val versionName: String,
        @SerialName("versionCode") val versionCode: Int,
        @SerialName("channel") val channel: String,
        @SerialName("mandatory") val mandatory: Boolean,
        @SerialName("sha256") val sha256: String,
        @SerialName("fileSizeBytes") val fileSizeBytes: String,
        @SerialName("signingCertSha256") val signingCertSha256: String,
        @SerialName("changelog") val changelog: String?,
        @SerialName("revocationEpoch") val revocationEpoch: Int,
    )
}
