package com.viwa.android.data.remote.telemetry.mvp.offline

import kotlinx.serialization.Serializable

@Serializable
data class TechnicianAllowlistWireRecordDto(
    val keyId: String,
    val fingerprint: String,
    val machineId: String? = null,
    val scopes: List<String>,
    val expiresAt: String? = null,
    val revision: String,
    val revocationEpoch: Int,
    val signature: String,
)

@Serializable
data class TechnicianAllowlistTombstoneDto(
    val keyId: String,
    val fingerprint: String,
    val revision: String,
    val revokedAt: String,
)

@Serializable
data class TechnicianAllowlistDeltaResponseDto(
    val records: List<TechnicianAllowlistWireRecordDto> = emptyList(),
    val tombstones: List<TechnicianAllowlistTombstoneDto> = emptyList(),
    val nextCursor: String,
    val revocationEpoch: Int = 0,
    val serverTimeUtc: String,
    val syncIntervalSeconds: Int = 300,
)

@Serializable
data class ValidateTechnicianKeyRequestDto(
    val code: String,
    val requestedScope: String,
    val requestUuid: String? = null,
)

@Serializable
data class ValidateTechnicianKeyResponseDto(
    val ok: Boolean,
    val technicianKeyId: String? = null,
    val scopes: List<String> = emptyList(),
    val sessionToken: String? = null,
    val expiresAt: String? = null,
    val code: String? = null,
    val reason: String? = null,
)

@Serializable
data class TechnicianAuditBatchItemDto(
    val requestUuid: String,
    val fingerprint: String,
    val action: String,
    val channel: String,
    val outcome: String,
    val failureCode: String? = null,
)

@Serializable
data class TechnicianAuditBatchRequestDto(
    val items: List<TechnicianAuditBatchItemDto>,
)

@Serializable
data class TechnicianAuditBatchItemResultDto(
    val requestUuid: String,
    val status: String,
    val rejectionCode: String? = null,
)

@Serializable
data class TechnicianAuditBatchResponseDto(
    val results: List<TechnicianAuditBatchItemResultDto>,
)

@Serializable
data class MvpTechnicianKeysCapabilityDto(
    val validateEndpoint: String,
    val allowlistDeltaEndpoint: String,
    val auditBatchEndpoint: String,
    val syncIntervalSeconds: Int = 300,
    val offlineScopes: List<String> = emptyList(),
    val onlineOnlyScopes: List<String> = emptyList(),
)
