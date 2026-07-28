package com.viwa.android.domain.technician

import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeyFeatureFlags

/** Server/local scope and feature-flag policy — conservative offline, server-wins online-only. */
object TechnicianKeyPolicyResolver {
    fun isFeatureEnabled(persistedServerEnabled: Boolean?): Boolean {
        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return false
        return persistedServerEnabled == true
    }

    fun isRuntimeEnabled(
        persistedServerEnabled: Boolean?,
        liveServerEnabled: Boolean?,
    ): Boolean {
        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return false
        val serverFlag = liveServerEnabled ?: persistedServerEnabled
        return serverFlag == true
    }

    fun effectiveOfflineScopes(serverOfflineScopes: List<String>): Set<String> {
        if (serverOfflineScopes.isEmpty()) return emptySet()
        return TechnicianKeyConstants.OFFLINE_SCOPES.intersect(serverOfflineScopes.toSet())
    }

    fun effectiveOnlineOnlyScopes(serverOnlineOnlyScopes: List<String>): Set<String> =
        TechnicianKeyConstants.ONLINE_ONLY_SCOPES.union(serverOnlineOnlyScopes.toSet())

    fun isOfflineScopeAllowed(
        requestedScope: String,
        serverOfflineScopes: List<String>,
        serverOnlineOnlyScopes: List<String>,
    ): Boolean {
        if (effectiveOnlineOnlyScopes(serverOnlineOnlyScopes).contains(requestedScope)) return false
        return effectiveOfflineScopes(serverOfflineScopes).contains(requestedScope)
    }

    fun isOnlineOnlyScope(
        requestedScope: String,
        serverOnlineOnlyScopes: List<String>,
    ): Boolean = effectiveOnlineOnlyScopes(serverOnlineOnlyScopes).contains(requestedScope)

    fun mergeCapabilityFromHello(
        persisted: MvpTechnicianKeysCapabilityDto?,
        incoming: MvpTechnicianKeysCapabilityDto?,
    ): MvpTechnicianKeysCapabilityDto? = incoming ?: persisted

    fun mapExplicitDenyCode(code: String?): TechnicianAuthorizationReason =
        when (code?.uppercase()) {
            "KEY_INVALID_FORMAT" -> TechnicianAuthorizationReason.KEY_INVALID_FORMAT
            "KEY_NOT_FOUND" -> TechnicianAuthorizationReason.KEY_NOT_FOUND
            "KEY_REVOKED" -> TechnicianAuthorizationReason.KEY_REVOKED
            "KEY_EXPIRED" -> TechnicianAuthorizationReason.KEY_EXPIRED
            "KEY_MACHINE_DENIED" -> TechnicianAuthorizationReason.KEY_MACHINE_DENIED
            "KEY_SCOPE_DENIED" -> TechnicianAuthorizationReason.KEY_SCOPE_DENIED
            "FEATURE_DISABLED", "NOT_FOUND" -> TechnicianAuthorizationReason.FEATURE_DISABLED
            else -> TechnicianAuthorizationReason.SERVER_DENIED
        }
}
