package com.viwa.android.domain.ota

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.repository.ConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Persists highest trusted OTA manifest revocation epoch to block rollback after restart. */
@Singleton
class OtaSigningPolicyStore
@Inject
constructor(
    private val configRepository: ConfigRepository,
) {
    private val lock = Any()

    @Volatile
    private var trustedRevocationEpoch: Int = 0

    suspend fun restore() {
        val persisted =
            configRepository
                .get(JsonStoreKeys.OTA_TRUSTED_REVOCATION_EPOCH)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
        synchronized(lock) {
            trustedRevocationEpoch = persisted
        }
    }

    fun getTrustedRevocationEpoch(): Int =
        synchronized(lock) {
            trustedRevocationEpoch
        }

    suspend fun markTrustedManifest(revocationEpoch: Int) {
        if (revocationEpoch < 0) return
        val nextEpoch =
            synchronized(lock) {
                if (revocationEpoch <= trustedRevocationEpoch) {
                    return
                }
                trustedRevocationEpoch = revocationEpoch
                trustedRevocationEpoch
            }
        configRepository.set(JsonStoreKeys.OTA_TRUSTED_REVOCATION_EPOCH, nextEpoch.toString())
    }
}
