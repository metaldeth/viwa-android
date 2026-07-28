package com.viwa.android.data.local.entitlement

import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineGrantWirePayloadDto
import com.viwa.android.domain.offline.OfflineEntitlementConstants.OFFLINE_CLOCK_SKEW_MS
import com.viwa.android.domain.offline.OfflineEntitlementConstants.STALE_GRANT_HARD_DENY_MS
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class EntitlementCacheStore
@Inject
constructor(
    private val dao: EntitlementCacheDao,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun upsertGrant(payload: OfflineGrantWirePayloadDto) {
        val now = clock()
        val entity =
            EntitlementCacheEntity(
                subjectHash = payload.subjectHash,
                machineId = payload.machineId,
                grantId = payload.grantId,
                subscriptionLevelId = payload.subscriptionLevelId,
                issuedAtMs = parseIsoMs(payload.issuedAt),
                expiresAtMs = parseIsoMs(payload.expiresAt),
                dailyRemainingMlAtIssue = payload.dailyRemainingMlAtIssue,
                maxOfflinePours = payload.maxOfflinePours,
                maxOfflineVolumeMl = payload.maxOfflineVolumeMl,
                signingKeyId = payload.signingKeyId,
                revocationEpoch = payload.revocationEpoch,
                revision = payload.revision,
                signature = payload.signature,
                grantJson = json.encodeToString(payload),
                revoked = false,
                updatedAtMs = now,
            )
        dao.upsert(entity)
    }

    suspend fun applyTombstone(grantId: String) {
        dao.markRevoked(grantId, clock())
    }

    suspend fun findValidGrant(subjectHash: String, machineId: String, nowMs: Long): EntitlementCacheEntity? {
        val row = dao.findBySubjectAndMachine(subjectHash, machineId) ?: return null
        if (row.revoked) return null
        if (row.expiresAtMs + STALE_GRANT_HARD_DENY_MS < nowMs) return null
        if (row.expiresAtMs < nowMs) return null
        return row
    }

    suspend fun invalidateSubject(subjectHash: String, machineId: String) {
        dao.invalidateSubject(subjectHash, machineId, clock())
    }

    suspend fun metricsSnapshot(nowMs: Long = clock()): EntitlementCacheMetrics {
        val count = dao.countActive(nowMs)
        val oldest = dao.oldestActiveUpdatedAtMs()
        val oldestAgeMs = if (oldest != null && oldest > 0) nowMs - oldest else null
        return EntitlementCacheMetrics(activeGrantCount = count, oldestGrantAgeMs = oldestAgeMs)
    }

    data class EntitlementCacheMetrics(
        val activeGrantCount: Int,
        val oldestGrantAgeMs: Long?,
    )

    private fun parseIsoMs(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
