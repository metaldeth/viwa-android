package com.viwa.android.data.remote.telemetry.mvp.offline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OfflineGrantWirePayloadDto(
    val grantId: String,
    val subjectHash: String,
    val machineId: String,
    val subscriptionLevelId: String,
    val issuedAt: String,
    val expiresAt: String,
    val dailyRemainingMlAtIssue: Int,
    val maxOfflinePours: Int,
    val maxOfflineVolumeMl: Int,
    val signingKeyId: String,
    val revocationEpoch: Int,
    val revision: String,
    val signature: String,
)

@Serializable
data class OfflineGrantTombstoneDto(
    val grantId: String,
    val subjectHash: String,
    val revision: String,
    val revokedAt: String,
)

@Serializable
data class OfflineGrantsDeltaResponseDto(
    val grants: List<OfflineGrantWirePayloadDto> = emptyList(),
    val tombstones: List<OfflineGrantTombstoneDto> = emptyList(),
    val nextCursor: String,
    val serverTimeUtc: String,
)

@Serializable
data class OfflineReconcileBatchItemDto(
    val requestUuid: String,
    val grantId: String,
    val soldAt: String,
    val volumeMl: Int,
    val saleId: String? = null,
    val drinkId: Int? = null,
)

@Serializable
data class OfflineReconcileBatchRequestDto(
    val items: List<OfflineReconcileBatchItemDto>,
)

@Serializable
data class OfflineReconcileItemResultDto(
    val requestUuid: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class OfflineReconcileBatchResponseDto(
    val results: List<OfflineReconcileItemResultDto>,
)

@Serializable
data class OfflineSigningPublicKeyDto(
    val keyId: String,
    val publicKeyPem: String,
    val revocationEpoch: Int,
)

@Serializable
data class MvpOfflineEntitlementCapabilityDto(
    val grantsDeltaEndpoint: String,
    val reconcileBatchEndpoint: String,
    val maxReconcileBatchSize: Int = 50,
)

@Serializable
data class MvpHelloFeatureFlagsDto(
    @SerialName("wsProtocolV3") val wsProtocolV3: Boolean = false,
    @SerialName("offlineEntitlement") val offlineEntitlement: Boolean = false,
    @SerialName("outboxRestSync") val outboxRestSync: Boolean = false,
    @SerialName("technicianKeys") val technicianKeys: Boolean = false,
    @SerialName("appUpdates") val appUpdates: Boolean = false,
)
