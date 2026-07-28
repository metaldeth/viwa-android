package com.viwa.android.data.local.technician

import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import com.viwa.android.domain.technician.TechnicianKeyPolicyResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TechnicianKeyPersistedPolicy(
    val serverTechnicianKeysEnabled: Boolean?,
    val offlineScopes: List<String>,
    val onlineOnlyScopes: List<String>,
    val capability: MvpTechnicianKeysCapabilityDto?,
    val hasTrustedAllowlistSync: Boolean,
    val lastSyncAtMs: Long,
    val revocationEpoch: Int,
)

@Singleton
class TechnicianKeyPolicyStore
@Inject
constructor(
    private val stateDao: TechnicianAllowlistStateDao,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun read(): TechnicianKeyPersistedPolicy {
        val state = stateDao.getState()
        return state.toPolicy()
    }

    suspend fun updateFromHello(
        serverTechnicianKeysEnabled: Boolean?,
        capability: MvpTechnicianKeysCapabilityDto?,
    ) {
        val current = stateDao.getState()
        val offlineScopes = capability?.offlineScopes ?: current?.offlineScopesJson?.let(::decodeScopes).orEmpty()
        val onlineOnlyScopes =
            capability?.onlineOnlyScopes ?: current?.onlineOnlyScopesJson?.let(::decodeScopes).orEmpty()
        val capabilityJson =
            capability?.let { json.encodeToString(it) }
                ?: current?.capabilityJson
        stateDao.upsert(
            (current ?: TechnicianAllowlistStateEntity()).copy(
                serverTechnicianKeysEnabled = serverTechnicianKeysEnabled ?: current?.serverTechnicianKeysEnabled,
                offlineScopesJson = json.encodeToString(offlineScopes),
                onlineOnlyScopesJson = json.encodeToString(onlineOnlyScopes),
                capabilityJson = capabilityJson,
                policyUpdatedAtMs = clock(),
            ),
        )
    }

    suspend fun markTrustedAllowlistSync(
        revocationEpoch: Int,
        lastSyncAtMs: Long = clock(),
    ) {
        val current = stateDao.getState() ?: TechnicianAllowlistStateEntity()
        stateDao.upsert(
            current.copy(
                hasTrustedAllowlistSync = true,
                lastSyncAtMs = lastSyncAtMs,
                revocationEpoch = revocationEpoch,
            ),
        )
    }

    suspend fun isOfflineFeatureEnabled(): Boolean {
        val policy = read()
        return TechnicianKeyPolicyResolver.isFeatureEnabled(policy.serverTechnicianKeysEnabled)
    }

    suspend fun persistedCapability(): MvpTechnicianKeysCapabilityDto? = read().capability

    private fun TechnicianAllowlistStateEntity?.toPolicy(): TechnicianKeyPersistedPolicy {
        val offlineScopes = this?.offlineScopesJson?.let(::decodeScopes).orEmpty()
        val onlineOnlyScopes = this?.onlineOnlyScopesJson?.let(::decodeScopes).orEmpty()
        val capability =
            this?.capabilityJson?.let {
                runCatching {
                    json.decodeFromString(MvpTechnicianKeysCapabilityDto.serializer(), it)
                }.getOrNull()
            }
        return TechnicianKeyPersistedPolicy(
            serverTechnicianKeysEnabled = this?.serverTechnicianKeysEnabled,
            offlineScopes = offlineScopes,
            onlineOnlyScopes = onlineOnlyScopes,
            capability = capability,
            hasTrustedAllowlistSync = this?.hasTrustedAllowlistSync == true,
            lastSyncAtMs = this?.lastSyncAtMs ?: 0L,
            revocationEpoch = this?.revocationEpoch ?: 0,
        )
    }

    private fun decodeScopes(raw: String): List<String> =
        runCatching {
            json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
}
