package com.viwa.android.domain.technician



import com.viwa.android.data.local.technician.TechnicianAllowlistEntity

import com.viwa.android.data.local.technician.TechnicianAllowlistStore

import com.viwa.android.data.local.technician.TechnicianAuditOutboxStore

import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore

import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeyFeatureFlags

import com.viwa.android.domain.offline.BoundedTelemetryClock

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.serialization.builtins.ListSerializer

import kotlinx.serialization.builtins.serializer

import kotlinx.serialization.json.Json

import timber.log.Timber



data class TechnicianAuthorizationResult(

    val allowed: Boolean,

    val reason: TechnicianAuthorizationReason,

    val technicianKeyId: String? = null,

    val scopes: List<String> = emptyList(),

    val sessionToken: String? = null,

    val expiresAtMs: Long? = null,

    val fingerprint: String? = null,

    val channel: String,

    /** True when online path failed due to transport/timeout — caller may attempt offline fallback. */

    val transportFailure: Boolean = false,

)



@Singleton

class TechnicianKeyAuthorizationService

@Inject

constructor(

    private val allowlistStore: TechnicianAllowlistStore,

    private val allowlistVerifier: TechnicianAllowlistVerifier,

    private val auditOutboxStore: TechnicianAuditOutboxStore,

    private val sessionStore: TechnicianSessionStore,

    private val policyStore: TechnicianKeyPolicyStore,

    private val clock: BoundedTelemetryClock,

    private val metrics: TechnicianKeyMetrics,

) {

    private val json =

        Json {

            ignoreUnknownKeys = true

        }



    suspend fun authorizeOffline(

        rawCode: String,

        machineId: String,

        requestedScope: String,

        requestUuid: String,

    ): TechnicianAuthorizationResult {

        val policy = policyStore.read()

        if (!TechnicianKeyPolicyResolver.isFeatureEnabled(policy.serverTechnicianKeysEnabled)) {

            return denyOffline(

                requestUuid,

                null,

                requestedScope,

                TechnicianAuthorizationReason.OFFLINE_POLICY_DISABLED,

                null,

            )

        }

        if (!policy.hasTrustedAllowlistSync || policy.lastSyncAtMs <= 0L) {

            return denyOffline(

                requestUuid,

                null,

                requestedScope,

                TechnicianAuthorizationReason.OFFLINE_NO_TRUSTED_SYNC,

                null,

            )

        }

        if (

            TechnicianKeyPolicyResolver.isOnlineOnlyScope(requestedScope, policy.onlineOnlyScopes) ||

            !TechnicianKeyPolicyResolver.isOfflineScopeAllowed(
                requestedScope,
                policy.offlineScopes,
                policy.onlineOnlyScopes,
            )

        ) {

            return denyOffline(

                requestUuid,

                null,

                requestedScope,

                TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED,

                null,

            )

        }

        val normalized = TechnicianKeyNormalizer.normalize(rawCode)

        if (!TechnicianKeyNormalizer.isValidFormat(normalized)) {

            return denyOffline(

                requestUuid,

                null,

                requestedScope,

                TechnicianAuthorizationReason.KEY_INVALID_FORMAT,

                null,

            )

        }

        val fingerprint = TechnicianKeyFingerprint.fingerprint(normalized)

        if (clock.isClockUnsafe()) {

            return denyOffline(

                requestUuid,

                fingerprint,

                requestedScope,

                TechnicianAuthorizationReason.OFFLINE_CLOCK_UNSAFE,

                null,

            )

        }

        val nowMs = clock.trustedNowMs()

        val cached = allowlistStore.findActiveByFingerprint(fingerprint)

            ?: return denyOffline(

                requestUuid,

                fingerprint,

                requestedScope,

                TechnicianAuthorizationReason.OFFLINE_NO_ALLOWLIST,

                null,

            )

        val validation = validateCachedRecord(cached, machineId, requestedScope, nowMs, policy)

        if (!validation.allowed) {

            return denyOffline(

                requestUuid,

                fingerprint,

                requestedScope,

                validation.reason,

                cached.keyId,

                validation.failureCode,

            )

        }

        val sessionToken = java.util.UUID.randomUUID().toString()

        val expiresAtMs = nowMs + TechnicianKeyConstants.SESSION_TTL_MS

        sessionStore.establish(

            technicianKeyId = cached.keyId,

            scopes = validation.scopes,

            sessionToken = sessionToken,

            expiresAtMs = expiresAtMs,

        )

        if (

            !auditOutboxStore.enqueueNew(

                requestUuid = requestUuid,

                fingerprint = fingerprint,

                technicianKeyId = cached.keyId,

                action = requestedScope,

                channel = "OFFLINE",

                outcome = "SUCCESS",

            )

        ) {

            sessionStore.clear()

            metrics.logValidation(reason = TechnicianAuthorizationReason.AUDIT_ENQUEUE_FAILED.name, channel = "OFFLINE")

            Timber.tag(TAG).w("offline deny: audit enqueue failed requestUuid=%s", requestUuid)

            return TechnicianAuthorizationResult(

                allowed = false,

                reason = TechnicianAuthorizationReason.AUDIT_ENQUEUE_FAILED,

                technicianKeyId = cached.keyId,

                fingerprint = fingerprint,

                channel = "OFFLINE",

            )

        }

        metrics.logValidation(reason = "offline_success", channel = "OFFLINE")

        return TechnicianAuthorizationResult(

            allowed = true,

            reason = TechnicianAuthorizationReason.GRANTED,

            technicianKeyId = cached.keyId,

            scopes = validation.scopes,

            sessionToken = sessionToken,

            expiresAtMs = expiresAtMs,

            fingerprint = fingerprint,

            channel = "OFFLINE",

        )

    }



    suspend fun recordOnlineOutcome(

        requestUuid: String,

        fingerprint: String?,

        technicianKeyId: String?,

        requestedScope: String,

        allowed: Boolean,

        reason: TechnicianAuthorizationReason,

        scopes: List<String> = emptyList(),

        sessionToken: String? = null,

        expiresAtMs: Long? = null,

    ): TechnicianAuthorizationResult {

        val outcome =

            when {

                allowed -> "SUCCESS"

                reason == TechnicianAuthorizationReason.KEY_SCOPE_DENIED ||

                    reason == TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED ||

                    reason == TechnicianAuthorizationReason.SERVER_DENIED -> "DENIED"

                else -> "DENIED"

            }

        fingerprint?.let {

            auditOutboxStore.enqueueNew(

                requestUuid = requestUuid,

                fingerprint = it,

                technicianKeyId = technicianKeyId,

                action = requestedScope,

                channel = "ONLINE",

                outcome = outcome,

                failureCode = if (allowed) null else reason.name,

            )

        }

        if (allowed && sessionToken != null && expiresAtMs != null && technicianKeyId != null) {

            sessionStore.establish(

                technicianKeyId = technicianKeyId,

                scopes = scopes,

                sessionToken = sessionToken,

                expiresAtMs = expiresAtMs,

            )

        }

        metrics.logValidation(reason = reason.name, channel = "ONLINE")

        return TechnicianAuthorizationResult(

            allowed = allowed,

            reason = reason,

            technicianKeyId = technicianKeyId,

            scopes = scopes,

            sessionToken = sessionToken,

            expiresAtMs = expiresAtMs,

            fingerprint = fingerprint,

            channel = "ONLINE",

        )

    }



    suspend fun validateCachedRecord(

        entity: TechnicianAllowlistEntity,

        machineId: String,

        requestedScope: String,

        nowMs: Long,

        policy: com.viwa.android.data.local.technician.TechnicianKeyPersistedPolicy? = null,

    ): CachedValidation {

        val activePolicy = policy ?: policyStore.read()

        if (entity.revoked) {

            return CachedValidation(false, TechnicianAuthorizationReason.KEY_REVOKED, emptyList(), "KEY_REVOKED")

        }

        val scopes = json.decodeFromString(ListSerializer(String.serializer()), entity.scopesJson)

        val effectiveOffline =

            TechnicianKeyPolicyResolver.effectiveOfflineScopes(activePolicy.offlineScopes)

        if (!effectiveOffline.contains(requestedScope) || !scopes.contains(requestedScope)) {

            return CachedValidation(false, TechnicianAuthorizationReason.KEY_SCOPE_DENIED, scopes, "KEY_SCOPE_DENIED")

        }

        if (entity.machineId != null && entity.machineId != machineId) {

            return CachedValidation(false, TechnicianAuthorizationReason.KEY_MACHINE_DENIED, scopes, "KEY_MACHINE_DENIED")

        }

        entity.expiresAtMs?.let { expires ->

            if (nowMs >= expires) {

                return CachedValidation(false, TechnicianAuthorizationReason.KEY_EXPIRED, scopes, "KEY_EXPIRED")

            }

        }

        val stateEpoch = allowlistStore.getRevocationEpoch()

        if (entity.revocationEpoch < stateEpoch) {

            return CachedValidation(false, TechnicianAuthorizationReason.KEY_REVOKED, scopes, "KEY_REVOKED")

        }

        if (

            !allowlistVerifier.verifyCachedRecord(

                keyId = entity.keyId,

                fingerprint = entity.fingerprint,

                machineId = entity.machineId,

                scopes = scopes,

                expiresAt = entity.expiresAtIso,

                revision = entity.revision,

                revocationEpoch = entity.revocationEpoch,

                signature = entity.signature,

            )

        ) {

            return CachedValidation(

                false,

                TechnicianAuthorizationReason.OFFLINE_STALE_ALLOWLIST,

                scopes,

                "SIGNATURE_INVALID",

            )

        }

        return CachedValidation(true, TechnicianAuthorizationReason.GRANTED, scopes, null)

    }



    data class CachedValidation(

        val allowed: Boolean,

        val reason: TechnicianAuthorizationReason,

        val scopes: List<String>,

        val failureCode: String?,

    )



    private suspend fun denyOffline(

        requestUuid: String,

        fingerprint: String?,

        requestedScope: String,

        reason: TechnicianAuthorizationReason,

        technicianKeyId: String?,

        failureCode: String? = reason.name,

    ): TechnicianAuthorizationResult {

        fingerprint?.let {

            auditOutboxStore.enqueueNew(

                requestUuid = requestUuid,

                fingerprint = it,

                technicianKeyId = technicianKeyId,

                action = requestedScope,

                channel = "OFFLINE",

                outcome = "DENIED",

                failureCode = failureCode,

            )

        }

        metrics.logValidation(reason = reason.name, channel = "OFFLINE")

        Timber.tag(TAG).i("offline deny reason=%s scope=%s", reason.name, requestedScope)

        return TechnicianAuthorizationResult(

            allowed = false,

            reason = reason,

            technicianKeyId = technicianKeyId,

            fingerprint = fingerprint,

            channel = "OFFLINE",

        )

    }



    companion object {

        private const val TAG = "TechKeyAuth"

    }

}



@Singleton

class TechnicianKeyMetrics

@Inject

constructor(

    private val allowlistStore: TechnicianAllowlistStore,

    private val auditOutboxStore: TechnicianAuditOutboxStore,

) {

    suspend fun logDepthSnapshot() {

        val allowlist = allowlistStore.metricsSnapshot()

        val auditDepth = auditOutboxStore.pendingCount()

        Timber.tag(TAG).i(

            "technician allowlist count=%d syncAgeMs=%s auditPending=%d epoch=%d",

            allowlist.activeRecordCount,

            allowlist.lastSyncAgeMs?.toString() ?: "n/a",

            auditDepth,

            allowlist.revocationEpoch,

        )

    }



    fun logValidation(reason: String, channel: String) {

        Timber.tag(TAG).d("validation channel=%s reason=%s", channel, reason)

    }



    companion object {

        private const val TAG = "TechKeyMetrics"

    }

}

