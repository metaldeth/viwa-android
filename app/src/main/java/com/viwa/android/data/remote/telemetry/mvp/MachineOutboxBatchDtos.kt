package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.mvp.offline.MvpOfflineEntitlementCapabilityDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MvpOutboxBatchCapabilityDto(
    val endpoint: String,
    val maxBatchSize: Int = 50,
    val supportedKinds: List<String> = emptyList(),
)

@Serializable
data class MvpHelloCapabilitiesDto(
    val outboxBatch: MvpOutboxBatchCapabilityDto? = null,
    val offlineEntitlement: MvpOfflineEntitlementCapabilityDto? = null,
    val technicianKeys: com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto? = null,
)

@Serializable
data class MachineOutboxBatchEntryDto(
    val kind: String,
    val messageId: String,
    val idempotencyKey: String,
    val sentAt: String,
    val payload: JsonObject,
)

@Serializable
data class MachineOutboxBatchRequestDto(
    val batchId: String,
    val entries: List<MachineOutboxBatchEntryDto>,
)

@Serializable
data class MachineOutboxItemResultDto(
    val messageId: String,
    val status: String,
    val code: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class MachineOutboxBatchResponseDto(
    val batchId: String,
    val results: List<MachineOutboxItemResultDto>,
)
