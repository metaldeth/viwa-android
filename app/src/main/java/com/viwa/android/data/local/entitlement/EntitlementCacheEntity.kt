package com.viwa.android.data.local.entitlement

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "entitlement_cache",
    primaryKeys = ["subject_hash", "machine_id"],
    indices = [
        Index(value = ["grant_id"], unique = true),
        Index(value = ["expires_at_ms"]),
        Index(value = ["revoked"]),
    ],
)
data class EntitlementCacheEntity(
    @ColumnInfo(name = "subject_hash") val subjectHash: String,
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "grant_id") val grantId: String,
    @ColumnInfo(name = "subscription_level_id") val subscriptionLevelId: String,
    @ColumnInfo(name = "issued_at_ms") val issuedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
    @ColumnInfo(name = "daily_remaining_ml_at_issue") val dailyRemainingMlAtIssue: Int,
    @ColumnInfo(name = "max_offline_pours") val maxOfflinePours: Int,
    @ColumnInfo(name = "max_offline_volume_ml") val maxOfflineVolumeMl: Int,
    @ColumnInfo(name = "signing_key_id") val signingKeyId: String,
    @ColumnInfo(name = "revocation_epoch") val revocationEpoch: Int,
    @ColumnInfo(name = "revision") val revision: String,
    @ColumnInfo(name = "signature") val signature: String,
    @ColumnInfo(name = "grant_json") val grantJson: String,
    @ColumnInfo(name = "revoked") val revoked: Boolean = false,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
