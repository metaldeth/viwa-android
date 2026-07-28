package com.viwa.android.data.remote.ota

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OtaReleaseChannel {
    @SerialName("STABLE")
    STABLE,

    @SerialName("BETA")
    BETA,
}

@Serializable
enum class OtaReportStatus {
    @SerialName("STARTED")
    STARTED,

    @SerialName("DOWNLOADING")
    DOWNLOADING,

    @SerialName("DOWNLOADED")
    DOWNLOADED,

    @SerialName("INSTALLING")
    INSTALLING,

    @SerialName("INSTALLED")
    INSTALLED,

    @SerialName("FAILED")
    FAILED,
}

@Serializable
data class OtaSignedManifestDto(
    val releaseId: String,
    val versionName: String,
    val versionCode: Int,
    val channel: OtaReleaseChannel,
    val mandatory: Boolean = false,
    val sha256: String,
    val fileSizeBytes: String,
    val signingCertSha256: String,
    val changelog: String? = null,
    val revocationEpoch: Int? = null,
    val manifestKeyId: String,
    val manifestSignature: String,
    val downloadUrl: String,
    val downloadExpiresAt: String,
)

@Serializable
data class OtaCheckResponseDto(
    val updateAvailable: Boolean,
    val manifest: OtaSignedManifestDto? = null,
)

@Serializable
data class OtaReportRequestDto(
    val requestUuid: String,
    val releaseId: String,
    val fromVersionCode: Int? = null,
    val toVersionCode: Int,
    val status: OtaReportStatus,
    val failureReason: String? = null,
)

@Serializable
data class OtaReportResponseDto(
    val requestUuid: String,
    val status: OtaReportStatus,
    val idempotent: Boolean = false,
)
