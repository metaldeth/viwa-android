package com.viwa.android.domain.ota

import com.viwa.android.BuildConfig
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import javax.inject.Inject
import javax.inject.Singleton

/** Pinned + hello-published Ed25519 keys for OTA manifest verification. */
@Singleton
class OtaSigningKeysStore
@Inject
constructor() {
    private val lock = Any()
    private val keysById = linkedMapOf<String, MutableList<OtaSigningPublicKey>>()

    init {
        registerPinnedFromBuildConfig()
    }

    fun updateFromHello(signingPublicKeys: List<OfflineSigningPublicKeyDto>?) {
        if (signingPublicKeys.isNullOrEmpty()) return
        synchronized(lock) {
            signingPublicKeys.forEach { dto ->
                keysById
                    .getOrPut(dto.keyId) { mutableListOf() }
                    .add(
                        OtaSigningPublicKey(
                            keyId = dto.keyId,
                            publicKeyPem = dto.publicKeyPem,
                            revocationEpoch = dto.revocationEpoch,
                        ),
                    )
            }
        }
    }

    fun findKeys(keyId: String): List<OtaSigningPublicKey> =
        synchronized(lock) {
            keysById[keyId]?.toList().orEmpty()
        }

    private fun registerPinnedFromBuildConfig() {
        val keyId = BuildConfig.OTA_SIGNING_KEY_ID.trim()
        val pem = BuildConfig.OTA_SIGNING_PUBLIC_KEY_PEM.trim()
        if (keyId.isBlank() || pem.isBlank()) return
        synchronized(lock) {
            keysById.getOrPut(keyId) { mutableListOf() }.add(
                OtaSigningPublicKey(
                    keyId = keyId,
                    publicKeyPem = pem,
                    revocationEpoch = 0,
                ),
            )
        }
    }
}

data class OtaSigningPublicKey(
    val keyId: String,
    val publicKeyPem: String,
    val revocationEpoch: Int,
)
