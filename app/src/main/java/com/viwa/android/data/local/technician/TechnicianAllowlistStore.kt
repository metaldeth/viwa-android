package com.viwa.android.data.local.technician

import androidx.room.withTransaction
import com.viwa.android.data.local.db.ViwaDatabase
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistTombstoneDto
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianAllowlistWireRecordDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class TechnicianAllowlistStore
@Inject
constructor(
    private val database: ViwaDatabase,
    private val allowlistDao: TechnicianAllowlistDao,
    private val stateDao: TechnicianAllowlistStateDao,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val clock: () -> Long = { System.currentTimeMillis() }

    suspend fun getCursor(): String = stateDao.getState()?.deltaCursor ?: "0"

    suspend fun getRevocationEpoch(): Int = stateDao.getState()?.revocationEpoch ?: 0

    suspend fun applyDeltaTransactionally(
        records: List<TechnicianAllowlistWireRecordDto>,
        tombstones: List<TechnicianAllowlistTombstoneDto>,
        nextCursor: String,
        revocationEpoch: Int,
    ) {
        val now = clock()
        val entities = records.map { it.toEntity(now) }
        database.withTransaction {
            for (entity in entities) {
                allowlistDao.upsert(entity)
            }
            for (tomb in tombstones) {
                allowlistDao.markRevokedByKeyId(tomb.keyId, now)
                allowlistDao.markRevokedByFingerprint(tomb.fingerprint, now)
            }
            val currentState = stateDao.getState() ?: TechnicianAllowlistStateEntity()
            stateDao.upsert(
                currentState.copy(
                    deltaCursor = nextCursor,
                    revocationEpoch = revocationEpoch,
                    lastSyncAtMs = now,
                ),
            )
        }
    }

    suspend fun findActiveByFingerprint(fingerprint: String): TechnicianAllowlistEntity? =
        allowlistDao.findActiveByFingerprint(fingerprint)

    suspend fun metricsSnapshot(nowMs: Long = clock()): TechnicianAllowlistMetrics {
        val count = allowlistDao.countActive()
        val oldest = allowlistDao.oldestActiveUpdatedAtMs()
        val state = stateDao.getState()
        val ageMs = if (oldest != null && oldest > 0) nowMs - oldest else null
        val syncAgeMs =
            if (state != null && state.lastSyncAtMs > 0) nowMs - state.lastSyncAtMs else null
        return TechnicianAllowlistMetrics(
            activeRecordCount = count,
            oldestRecordAgeMs = ageMs,
            lastSyncAgeMs = syncAgeMs,
            revocationEpoch = state?.revocationEpoch ?: 0,
        )
    }

    data class TechnicianAllowlistMetrics(
        val activeRecordCount: Int,
        val oldestRecordAgeMs: Long?,
        val lastSyncAgeMs: Long?,
        val revocationEpoch: Int,
    )

    private fun TechnicianAllowlistWireRecordDto.toEntity(nowMs: Long): TechnicianAllowlistEntity =
        TechnicianAllowlistEntity(
            fingerprint = fingerprint,
            keyId = keyId,
            machineId = machineId,
            scopesJson = json.encodeToString(scopes),
            expiresAtMs = expiresAt?.let { parseIsoMs(it) },
            expiresAtIso = expiresAt,
            revocationEpoch = revocationEpoch,
            revision = revision,
            signature = signature,
            recordJson = json.encodeToString(this),
            revoked = false,
            updatedAtMs = nowMs,
        )

    private fun parseIsoMs(iso: String): Long = Instant.parse(iso).toEpochMilli()
}
