package com.viwa.android.data.local.technician

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "technician_allowlist_cache",
    primaryKeys = ["fingerprint"],
    indices = [
        Index(value = ["key_id"], unique = true),
        Index(value = ["expires_at_ms"]),
        Index(value = ["revoked"]),
    ],
)
data class TechnicianAllowlistEntity(
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "key_id") val keyId: String,
    @ColumnInfo(name = "machine_id") val machineId: String?,
    @ColumnInfo(name = "scopes_json") val scopesJson: String,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    /** Original server ISO `expiresAt` for canonical signature verification — never reformat from millis. */
    @ColumnInfo(name = "expires_at_iso") val expiresAtIso: String? = null,
    @ColumnInfo(name = "revocation_epoch") val revocationEpoch: Int,
    @ColumnInfo(name = "revision") val revision: String,
    @ColumnInfo(name = "signature") val signature: String,
    @ColumnInfo(name = "record_json") val recordJson: String,
    @ColumnInfo(name = "revoked") val revoked: Boolean = false,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "technician_allowlist_state")
data class TechnicianAllowlistStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "delta_cursor") val deltaCursor: String = "0",
    @ColumnInfo(name = "revocation_epoch") val revocationEpoch: Int = 0,
    @ColumnInfo(name = "last_sync_at_ms") val lastSyncAtMs: Long = 0L,
    /** Explicit server hello `featureFlags.technicianKeys`; null = unknown/legacy → fail closed. */
    @ColumnInfo(name = "server_technician_keys_enabled") val serverTechnicianKeysEnabled: Boolean? = null,
    @ColumnInfo(name = "offline_scopes_json") val offlineScopesJson: String = "[]",
    @ColumnInfo(name = "online_only_scopes_json") val onlineOnlyScopesJson: String = "[]",
    @ColumnInfo(name = "capability_json") val capabilityJson: String? = null,
    @ColumnInfo(name = "has_trusted_allowlist_sync") val hasTrustedAllowlistSync: Boolean = false,
    @ColumnInfo(name = "policy_updated_at_ms") val policyUpdatedAtMs: Long = 0L,
)

@Entity(
    tableName = "technician_audit_outbox",
    primaryKeys = ["request_uuid"],
    indices = [Index(value = ["sync_status"])],
)
data class TechnicianAuditOutboxEntity(
    @ColumnInfo(name = "request_uuid") val requestUuid: String,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "technician_key_id") val technicianKeyId: String?,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "channel") val channel: String,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SYNC_PENDING,
) {
    companion object {
        const val SYNC_PENDING = "PENDING"
        const val SYNC_SYNCED = "SYNCED"
        const val SYNC_REJECTED = "REJECTED"
    }
}
