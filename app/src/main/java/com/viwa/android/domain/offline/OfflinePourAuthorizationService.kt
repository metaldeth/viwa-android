package com.viwa.android.domain.offline

import com.viwa.android.data.local.entitlement.EntitlementCacheStore
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerState
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerStore
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineEntitlementFeatureFlags
import com.viwa.android.domain.offline.OfflineEntitlementConstants.OFFLINE_CLOCK_SKEW_MS
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

data class OfflinePourAuthorizationResult(
    val allowed: Boolean,
    val reason: OfflineAuthorizationReason,
    val grantId: String? = null,
    val remainingDailyMl: Int = 0,
    val remainingPours: Int = 0,
    val remainingVolumeMl: Int = 0,
)

@Singleton
class OfflinePourAuthorizationService
@Inject
constructor(
    private val cacheStore: EntitlementCacheStore,
    private val ledgerStore: OfflineUsageLedgerStore,
    private val grantVerifier: OfflineGrantVerifier,
    private val clock: BoundedTelemetryClock,
    private val metrics: OfflineEntitlementMetrics,
) {
    suspend fun authorizePour(
        clientId: String,
        machineId: String,
        volumeMl: Int,
    ): OfflinePourAuthorizationResult {
        if (!OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) {
            return deny(OfflineAuthorizationReason.OFFLINE_FEATURE_DISABLED)
        }
        if (clock.isClockUnsafe()) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE)
            return deny(OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE)
        }
        val subjectHash = SubjectHashUtil.computeSubjectHash(clientId)
        val nowMs = clock.trustedNowMs()
        val grant = cacheStore.findValidGrant(subjectHash, machineId, nowMs)
            ?: run {
                metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_NO_GRANT)
                return deny(OfflineAuthorizationReason.OFFLINE_NO_GRANT)
            }

        if (grant.revoked) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_GRANT_REVOKED)
            return deny(OfflineAuthorizationReason.OFFLINE_GRANT_REVOKED)
        }
        if (grant.expiresAtMs + OFFLINE_CLOCK_SKEW_MS < nowMs) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_GRANT_EXPIRED)
            return deny(OfflineAuthorizationReason.OFFLINE_GRANT_EXPIRED)
        }
        if (grant.issuedAtMs - OFFLINE_CLOCK_SKEW_MS > nowMs) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE)
            return deny(OfflineAuthorizationReason.OFFLINE_CLOCK_UNSAFE)
        }
        if (!grantVerifier.verifyCachedGrant(grant)) {
            cacheStore.invalidateSubject(subjectHash, machineId)
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_GRANT_TAMPERED)
            return deny(OfflineAuthorizationReason.OFFLINE_GRANT_TAMPERED)
        }
        if (grant.maxOfflinePours <= 0 || grant.maxOfflineVolumeMl <= 0 || grant.dailyRemainingMlAtIssue <= 0) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_DISABLED)
            return deny(OfflineAuthorizationReason.OFFLINE_DISABLED)
        }

        val ledgerRows = ledgerStore.listNonRejectedForGrant(grant.grantId)
        val finalizedPours =
            ledgerRows.count {
                it.state == OfflineUsageLedgerState.FINALIZED.name ||
                    it.state == OfflineUsageLedgerState.ENQUEUED.name ||
                    it.state == OfflineUsageLedgerState.ACKED.name ||
                    it.state == OfflineUsageLedgerState.CONFLICT.name
            }
        val reservedOrPouring =
            ledgerRows.count {
                it.state == OfflineUsageLedgerState.RESERVED.name ||
                    it.state == OfflineUsageLedgerState.POURING.name
            }
        val usedVolume =
            ledgerRows.sumOf { row ->
                when (row.state) {
                    OfflineUsageLedgerState.FINALIZED.name,
                    OfflineUsageLedgerState.ENQUEUED.name,
                    OfflineUsageLedgerState.ACKED.name,
                    OfflineUsageLedgerState.CONFLICT.name,
                    -> row.finalizedVolumeMl ?: row.requestedVolumeMl
                    OfflineUsageLedgerState.RESERVED.name,
                    OfflineUsageLedgerState.POURING.name,
                    -> row.requestedVolumeMl
                    else -> 0
                }
            }

        val totalPours = finalizedPours + reservedOrPouring
        if (totalPours >= grant.maxOfflinePours) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_POUR_LIMIT)
            return deny(OfflineAuthorizationReason.OFFLINE_POUR_LIMIT, grant.grantId)
        }
        if (volumeMl > grant.maxOfflineVolumeMl) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT)
            return deny(OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT, grant.grantId)
        }
        if (usedVolume + volumeMl > grant.dailyRemainingMlAtIssue) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_DAILY_EXCEEDED)
            return deny(OfflineAuthorizationReason.OFFLINE_DAILY_EXCEEDED, grant.grantId)
        }
        if (usedVolume + volumeMl > grant.maxOfflineVolumeMl) {
            metrics.recordAuthorization(OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT)
            return deny(OfflineAuthorizationReason.OFFLINE_VOLUME_LIMIT, grant.grantId)
        }

        metrics.recordAuthorization(OfflineAuthorizationReason.GRANTED)
        return OfflinePourAuthorizationResult(
            allowed = true,
            reason = OfflineAuthorizationReason.GRANTED,
            grantId = grant.grantId,
            remainingDailyMl = (grant.dailyRemainingMlAtIssue - usedVolume - volumeMl).coerceAtLeast(0),
            remainingPours = (grant.maxOfflinePours - totalPours - 1).coerceAtLeast(0),
            remainingVolumeMl = (grant.maxOfflineVolumeMl - usedVolume - volumeMl).coerceAtLeast(0),
        )
    }

    suspend fun buildOfflineSubscribeInfo(clientId: String, machineId: String): SubscribeInfoFromGrant? {
        val subjectHash = SubjectHashUtil.computeSubjectHash(clientId)
        val nowMs = clock.trustedNowMs()
        val grant = cacheStore.findValidGrant(subjectHash, machineId, nowMs) ?: return null
        if (!grantVerifier.verifyCachedGrant(grant)) return null
        val ledgerRows = ledgerStore.listNonRejectedForGrant(grant.grantId)
        val usedVolume =
            ledgerRows.sumOf { row ->
                row.finalizedVolumeMl ?: if (row.state != OfflineUsageLedgerState.REJECTED.name) row.requestedVolumeMl else 0
            }
        val remaining = (grant.dailyRemainingMlAtIssue - usedVolume).coerceAtLeast(0)
        return SubscribeInfoFromGrant(
            clientId = clientId,
            dailyRemainingMl = remaining,
            dailyLimitMl = grant.dailyRemainingMlAtIssue,
            expiresAtMs = grant.expiresAtMs,
        )
    }

    data class SubscribeInfoFromGrant(
        val clientId: String,
        val dailyRemainingMl: Int,
        val dailyLimitMl: Int,
        val expiresAtMs: Long,
    )

    private fun deny(reason: OfflineAuthorizationReason, grantId: String? = null): OfflinePourAuthorizationResult =
        OfflinePourAuthorizationResult(allowed = false, reason = reason, grantId = grantId)

    companion object {
        private const val TAG = "OfflinePourAuth"
    }
}

@Singleton
class OfflineEntitlementMetrics
@Inject
constructor(
    private val cacheStore: EntitlementCacheStore,
    private val ledgerStore: OfflineUsageLedgerStore,
) {
    @Volatile private var lastAuthReason: OfflineAuthorizationReason? = null
    @Volatile private var syncConflictCount: Int = 0

    fun recordAuthorization(reason: OfflineAuthorizationReason) {
        lastAuthReason = reason
        Timber.tag(TAG).i("offline_auth reason=%s", reason.name)
    }

    fun recordSyncConflict() {
        syncConflictCount++
        Timber.tag(TAG).w("offline_reconcile conflict total=%d", syncConflictCount)
    }

    suspend fun logDepthSnapshot() {
        val cache = cacheStore.metricsSnapshot()
        val ledger = ledgerStore.metricsSnapshot()
        Timber.tag(TAG).i(
            "offline_metrics grants=%d grantAgeMs=%s ledgerAwaiting=%d reserved=%d pouring=%d conflict=%d rejected=%d lastAuth=%s syncConflicts=%d",
            cache.activeGrantCount,
            cache.oldestGrantAgeMs?.toString() ?: "n/a",
            ledger.awaitingReconcile,
            ledger.reserved,
            ledger.pouring,
            ledger.conflict,
            ledger.rejected,
            lastAuthReason?.name ?: "n/a",
            syncConflictCount,
        )
    }

    companion object {
        private const val TAG = "OfflineEntitlement"
    }
}
