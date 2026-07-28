package com.viwa.android.domain.technician

import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistWireRecordDto
import com.viwa.android.domain.offline.OfflineSigningKeysStore
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import timber.log.Timber

@Singleton
class TechnicianAllowlistVerifier
@Inject
constructor(
    private val signingKeysStore: OfflineSigningKeysStore,
) {
    fun verifyRecord(record: TechnicianAllowlistWireRecordDto): Boolean {
        val fields = record.toCanonicalFields()
        return verifySignature(fields, record.signature)
    }

    fun verifyCachedRecord(
        keyId: String,
        fingerprint: String,
        machineId: String?,
        scopes: List<String>,
        expiresAt: String?,
        revision: String,
        revocationEpoch: Int,
        signature: String,
    ): Boolean {
        val fields =
            TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields(
                keyId = keyId,
                fingerprint = fingerprint,
                machineId = machineId,
                scopes = scopes,
                expiresAt = expiresAt,
                revision = revision,
                revocationEpoch = revocationEpoch,
            )
        return verifySignature(fields, signature)
    }

    private fun verifySignature(
        fields: TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields,
        signatureBase64Url: String,
    ): Boolean {
        val keys = signingKeysStore.allSigningKeys()
        if (keys.isEmpty()) return false
        val message = TechnicianAllowlistCanonicalSigning.buildCanonicalMessage(fields)
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
        private const val TAG = "TechAllowlistVerify"

        fun TechnicianAllowlistWireRecordDto.toCanonicalFields(): TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields =
            TechnicianAllowlistCanonicalSigning.CanonicalAllowlistFields(
                keyId = keyId,
                fingerprint = fingerprint,
                machineId = machineId,
                scopes = scopes,
                expiresAt = expiresAt,
                revision = revision,
                revocationEpoch = revocationEpoch,
            )
    }
}
