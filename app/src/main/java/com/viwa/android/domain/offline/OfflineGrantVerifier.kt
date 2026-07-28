package com.viwa.android.domain.offline

import com.viwa.android.data.local.entitlement.EntitlementCacheEntity
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineGrantWirePayloadDto
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import timber.log.Timber

@Singleton
class OfflineGrantVerifier
@Inject
constructor(
    private val signingKeysStore: OfflineSigningKeysStore,
) {
    fun verifyGrantPayload(payload: OfflineGrantWirePayloadDto): Boolean {
        val fields = payload.toCanonicalFields()
        return verifySignature(fields, payload.signature)
    }

    fun verifyCachedGrant(entity: EntitlementCacheEntity): Boolean {
        val fields =
            OfflineGrantCanonicalSigning.CanonicalGrantFields(
                grantId = entity.grantId,
                machineId = entity.machineId,
                subjectHash = entity.subjectHash,
                subscriptionLevelId = entity.subscriptionLevelId,
                issuedAt = isoFromMs(entity.issuedAtMs),
                expiresAt = isoFromMs(entity.expiresAtMs),
                dailyRemainingMlAtIssue = entity.dailyRemainingMlAtIssue,
                maxOfflinePours = entity.maxOfflinePours,
                maxOfflineVolumeMl = entity.maxOfflineVolumeMl,
                signingKeyId = entity.signingKeyId,
                revocationEpoch = entity.revocationEpoch,
            )
        return verifySignature(fields, entity.signature)
    }

    private fun verifySignature(
        fields: OfflineGrantCanonicalSigning.CanonicalGrantFields,
        signatureBase64Url: String,
    ): Boolean {
        val keys = signingKeysStore.findKeys(fields.signingKeyId)
        if (keys.isEmpty()) return false
        val message = OfflineGrantCanonicalSigning.buildCanonicalMessage(fields)
        val signature =
            runCatching {
                Base64.getUrlDecoder().decode(signatureBase64Url)
            }.getOrElse {
                return false
            }
        for (key in keys) {
            if (fields.revocationEpoch < key.revocationEpoch) continue
            if (verifyEd25519(key.publicKeyPem, message, signature)) {
                return true
            }
        }
        return false
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
        private const val TAG = "OfflineGrantVerifier"

        fun OfflineGrantWirePayloadDto.toCanonicalFields(): OfflineGrantCanonicalSigning.CanonicalGrantFields =
            OfflineGrantCanonicalSigning.CanonicalGrantFields(
                grantId = grantId,
                machineId = machineId,
                subjectHash = subjectHash,
                subscriptionLevelId = subscriptionLevelId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                dailyRemainingMlAtIssue = dailyRemainingMlAtIssue,
                maxOfflinePours = maxOfflinePours,
                maxOfflineVolumeMl = maxOfflineVolumeMl,
                signingKeyId = signingKeyId,
                revocationEpoch = revocationEpoch,
            )

        private fun isoFromMs(ms: Long): String = TelemetryIsoTimestamps.fromEpochMillis(ms)
    }
}

@Singleton
class OfflineSigningKeysStore
@Inject
constructor() {
    private val lock = Any()
    private var globalRevocationEpoch: Int = 0
    private val keysById = linkedMapOf<String, MutableList<OfflineSigningPublicKeyDto>>()

    fun updateFromHello(
        signingPublicKeys: List<OfflineSigningPublicKeyDto>,
        revocationEpoch: Int,
    ) {
        synchronized(lock) {
            globalRevocationEpoch = revocationEpoch
            keysById.clear()
            signingPublicKeys.forEach { key ->
                keysById.getOrPut(key.keyId) { mutableListOf() }.add(key)
            }
        }
    }

    fun findKeys(keyId: String): List<OfflineSigningPublicKeyDto> =
        synchronized(lock) {
            keysById[keyId]?.toList().orEmpty()
        }

    fun globalRevocationEpoch(): Int =
        synchronized(lock) {
            globalRevocationEpoch
        }

    fun allSigningKeys(): List<OfflineSigningPublicKeyDto> =
        synchronized(lock) {
            keysById.values.flatten()
        }
}
