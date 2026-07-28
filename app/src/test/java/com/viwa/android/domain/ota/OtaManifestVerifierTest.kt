package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel
import com.viwa.android.data.remote.ota.OtaSignedManifestDto
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.repository.ConfigRepository
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OtaManifestVerifierTest {
    private lateinit var keysStore: OtaSigningKeysStore
    private lateinit var policyStore: OtaSigningPolicyStore
    private lateinit var verifier: OtaManifestVerifier
    private lateinit var publicPem: String
    private lateinit var privatePkcs8: ByteArray
    private val keyId = "test-ota-key"
    private val rotationKeyId = "test-ota-key-v2"
    private lateinit var rotationPublicPem: String
    private lateinit var rotationPrivatePkcs8: ByteArray
    private val fixedClock =
        object : com.viwa.android.data.remote.telemetry.mvp.EpochMillisClock {
            override fun epochMillis(): Long = TelemetryIsoTimestamps.parseUtcToEpochMillis("2026-01-01T00:00:00.000Z")
        }

    @Before
    fun setUp() {
        keysStore = OtaSigningKeysStore()
        policyStore =
            OtaSigningPolicyStore(
                object : ConfigRepository {
                    private val values = mutableMapOf<String, String>()

                    override suspend fun get(key: String): String? = values[key]

                    override suspend fun set(key: String, value: String) {
                        values[key] = value
                    }

                    override suspend fun delete(key: String) {
                        values.remove(key)
                    }

                    override suspend fun getJson(key: String): String? = values[key]

                    override suspend fun setJson(key: String, json: String) {
                        values[key] = json
                    }
                },
            )
        runBlocking { policyStore.restore() }

        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        publicPem = pemFromEncoded(pair.public.encoded)
        privatePkcs8 = pair.private.encoded

        val rotationPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        rotationPublicPem = pemFromEncoded(rotationPair.public.encoded)
        rotationPrivatePkcs8 = rotationPair.private.encoded

        keysStore.updateFromHello(
            listOf(
                com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto(
                    keyId = keyId,
                    publicKeyPem = publicPem,
                    revocationEpoch = 0,
                ),
                com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto(
                    keyId = rotationKeyId,
                    publicKeyPem = rotationPublicPem,
                    revocationEpoch = 1,
                ),
            ),
        )
        verifier = OtaManifestVerifier(keysStore, policyStore, fixedClock)
    }

    @Test
    fun `valid epoch 0 manifest passes verification`() {
        val manifest = signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
        assertTrue(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `epoch bump with new key passes verification`() {
        val manifest =
            signedManifest(
                revocationEpoch = 1,
                keyId = rotationKeyId,
                privateKey = rotationPrivatePkcs8,
            )
        assertTrue(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `revoked key is rejected`() {
        val manifest = signedManifest(revocationEpoch = 0, keyId = rotationKeyId, privateKey = rotationPrivatePkcs8)
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `replay lower epoch rejected after persisted bump`() {
        runBlocking { policyStore.markTrustedManifest(1) }
        val manifest = signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `tampered revocation epoch fails signature verification`() {
        val manifest =
            signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
                .copy(revocationEpoch = 1)
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `missing revocation epoch fails closed`() {
        val manifest =
            signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
                .copy(revocationEpoch = null)
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `process restart persistence keeps trusted epoch`() {
        runBlocking { policyStore.markTrustedManifest(1) }
        val restoredPolicyStore =
            OtaSigningPolicyStore(
                object : ConfigRepository {
                    override suspend fun get(key: String): String? =
                        if (key == com.viwa.android.data.local.db.JsonStoreKeys.OTA_TRUSTED_REVOCATION_EPOCH) {
                            "1"
                        } else {
                            null
                        }

                    override suspend fun set(key: String, value: String) = Unit

                    override suspend fun delete(key: String) = Unit

                    override suspend fun getJson(key: String): String? = null

                    override suspend fun setJson(key: String, json: String) = Unit
                },
            )
        runBlocking { restoredPolicyStore.restore() }
        val restoredVerifier = OtaManifestVerifier(keysStore, restoredPolicyStore, fixedClock)
        val manifest = signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
        assertFalse(restoredVerifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `tampered manifest fails verification`() {
        val manifest =
            signedManifest(revocationEpoch = 0, keyId = keyId, privateKey = privatePkcs8)
                .copy(versionCode = 999)
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    @Test
    fun `expired download url fails verification`() {
        val manifest =
            signedManifest(
                revocationEpoch = 0,
                keyId = keyId,
                privateKey = privatePkcs8,
                downloadExpiresAt = "2020-01-01T00:00:00Z",
            )
        assertFalse(verifier.verifyManifest(manifest).isSuccess)
    }

    private fun signedManifest(
        revocationEpoch: Int,
        keyId: String,
        privateKey: ByteArray,
        downloadExpiresAt: String = "2030-01-01T00:00:00Z",
        changelog: String? = null,
    ): OtaSignedManifestDto {
        val fields =
            OtaManifestCanonicalSigning.CanonicalManifestFields(
                releaseId = "11111111-1111-1111-1111-111111111111",
                versionName = "2.0.0",
                versionCode = 200,
                channel = OtaReleaseChannel.STABLE,
                mandatory = false,
                sha256 = "deadbeef",
                fileSizeBytes = "1024",
                signingCertSha256 = "cafebabe",
                changelog = changelog,
                revocationEpoch = revocationEpoch,
            )
        val message = OtaManifestCanonicalSigning.buildCanonicalMessage(fields)
        val signature = sign(message, privateKey)
        return OtaSignedManifestDto(
            releaseId = fields.releaseId,
            versionName = fields.versionName,
            versionCode = fields.versionCode,
            channel = fields.channel,
            mandatory = fields.mandatory,
            sha256 = fields.sha256,
            fileSizeBytes = fields.fileSizeBytes,
            signingCertSha256 = fields.signingCertSha256,
            changelog = changelog,
            revocationEpoch = revocationEpoch,
            manifestKeyId = keyId,
            manifestSignature = signature,
            downloadUrl = "https://example.com/dl",
            downloadExpiresAt = downloadExpiresAt,
        )
    }

    private fun sign(message: ByteArray, privatePkcs8: ByteArray): String {
        val seed = privatePkcs8.copyOfRange(privatePkcs8.size - 32, privatePkcs8.size)
        val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
    }

    private fun pemFromEncoded(encoded: ByteArray): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) +
            "\n-----END PUBLIC KEY-----"
}
