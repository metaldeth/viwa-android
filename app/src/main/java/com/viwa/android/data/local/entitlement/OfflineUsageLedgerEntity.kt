package com.viwa.android.data.local.entitlement

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "offline_usage_ledger",
    primaryKeys = ["request_uuid"],
    indices = [
        Index(value = ["grant_id"]),
        Index(value = ["subject_hash"]),
        Index(value = ["state"]),
        Index(value = ["sale_id"]),
    ],
)
data class OfflineUsageLedgerEntity(
    @ColumnInfo(name = "request_uuid") val requestUuid: String,
    @ColumnInfo(name = "grant_id") val grantId: String,
    @ColumnInfo(name = "subject_hash") val subjectHash: String,
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "sale_id") val saleId: String,
    @ColumnInfo(name = "drink_id") val drinkId: Int?,
    @ColumnInfo(name = "requested_volume_ml") val requestedVolumeMl: Int,
    @ColumnInfo(name = "finalized_volume_ml") val finalizedVolumeMl: Int?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "sold_at_ms") val soldAtMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "reconcile_code") val reconcileCode: String? = null,
    @ColumnInfo(name = "reconcile_message") val reconcileMessage: String? = null,
)
