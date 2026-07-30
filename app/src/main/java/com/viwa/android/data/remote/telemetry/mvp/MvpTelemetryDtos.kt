package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.mvp.offline.MvpOfflineEntitlementCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ReserveSerialRequestDto(
    val installationId: String? = null,
)

@Serializable
data class ReserveSerialResponseDto(
    val serialNumber: String,
    val reservationToken: String,
    val expiresAt: String,
)

@Serializable
data class EnrollDeviceDto(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
)

@Serializable
data class EnrollAppDto(
    val versionName: String,
    val versionCode: Int,
    @SerialName("package") val packageName: String = "",
)

@Serializable
data class EnrollRequestDto(
    val installationId: String,
    val serialNumber: String,
    val reservationToken: String? = null,
    val credential: String,
    val rebind: Boolean = false,
    val device: EnrollDeviceDto,
    val app: EnrollAppDto,
)

@Serializable
data class EnrollResponseDto(
    val machineId: String,
    val serialNumber: String,
    val wsUrl: String? = null,
    val wsProtocolUrl: String? = null,
    val protocolVersion: Int? = null,
    val heartbeatIntervalSeconds: Int? = null,
)

@Serializable
data class ProvisionRequestDto(
    val installationId: String,
    val device: EnrollDeviceDto? = null,
    val app: EnrollAppDto,
)

@Serializable
data class RegisterRequestDto(
    val registrationKey: String,
    val serialNumber: String,
    val installationId: String,
    val device: EnrollDeviceDto? = null,
    val app: EnrollAppDto,
)

@Serializable
data class RegisterResponseDto(
    val id: String,
    val machineId: String,
    val serialNumber: String,
    val installationId: String,
    val machineSecret: String,
    val tokenEndpoint: String,
    val wsUrl: String,
    val protocolVersion: Int,
    val heartbeatIntervalSeconds: Int,
    val registrationKey: String? = null,
)

@Serializable
data class TokenRequestDto(
    val serialNumber: String,
    val machineSecret: String,
)

@Serializable
data class TokenResponseDto(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Int,
)

@Serializable
data class EnrollErrorBodyDto(
    val code: String? = null,
    val message: String? = null,
)

/** Исходящий/входящий WS-конверт simple-telemetry MVP. */
@Serializable
data class MvpWsEnvelopeDto(
    val type: String,
    val messageId: String,
    val sentAt: String,
    val payload: JsonElement? = null,
    val correlationId: String? = null,
)

@Serializable
data class MvpHelloPayloadDto(
    val serialNumber: String,
    val protocolVersion: Int = 1,
    val heartbeatIntervalSeconds: Int = 10,
    val serverTimeUtc: String? = null,
    val revocationEpoch: Int? = null,
    val signingPublicKeys: List<OfflineSigningPublicKeyDto>? = null,
    val otaSigningPublicKeys: List<OfflineSigningPublicKeyDto>? = null,
    val capabilities: MvpHelloCapabilitiesDto? = null,
    val featureFlags: com.viwa.android.data.remote.telemetry.mvp.offline.MvpHelloFeatureFlagsDto? = null,
)

@Serializable
data class MvpHeartbeatPayloadDto(
    val state: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val temperatureC: Double? = null,
)
