package com.viwa.android.domain.ota

import com.viwa.android.data.remote.ota.OtaReleaseChannel
import com.viwa.android.data.remote.ota.OtaSignedManifestDto
import com.viwa.android.domain.model.AppUpdate
import kotlinx.serialization.Serializable

enum class AppUpdatePhase {
    Idle,
    Checking,
    Offered,
    Downloading,
    Verifying,
    Installing,
    AwaitingUser,
    Success,
    Failed,
}

/** Blocks concurrent download/install (not manual check). */
fun AppUpdatePhase.blocksDownloadOrInstall(): Boolean =
    when (this) {
        AppUpdatePhase.Downloading,
        AppUpdatePhase.Verifying,
        AppUpdatePhase.Installing,
        AppUpdatePhase.AwaitingUser,
        -> true
        else -> false
    }

/** UI guard: disable install button while update pipeline is active. */
fun AppUpdatePhase.isInstallUiBusy(): Boolean = blocksDownloadOrInstall()

data class OtaUpdateOffer(
    val releaseId: String,
    val versionName: String,
    val versionCode: Int,
    val channel: OtaReleaseChannel,
    val mandatory: Boolean,
    val sha256: String,
    val fileSizeBytes: Long,
    val signingCertSha256: String,
    val changelog: String,
    val manifestKeyId: String,
    val manifestSignature: String,
    val downloadUrl: String,
    val downloadExpiresAt: String,
) {
    fun toAppUpdate(): AppUpdate =
        AppUpdate(
            version = versionName,
            versionCode = versionCode,
            url = downloadUrl,
            changelog = changelog,
            channel = channel.name,
            releaseId = releaseId,
        )

    companion object {
        fun fromManifest(manifest: OtaSignedManifestDto): OtaUpdateOffer =
            OtaUpdateOffer(
                releaseId = manifest.releaseId,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                channel = manifest.channel,
                mandatory = manifest.mandatory,
                sha256 = manifest.sha256.lowercase(),
                fileSizeBytes = manifest.fileSizeBytes.toLong(),
                signingCertSha256 = manifest.signingCertSha256.lowercase(),
                changelog = manifest.changelog.orEmpty(),
                manifestKeyId = manifest.manifestKeyId,
                manifestSignature = manifest.manifestSignature,
                downloadUrl = manifest.downloadUrl,
                downloadExpiresAt = manifest.downloadExpiresAt,
            )
    }
}

data class AppUpdateCoordinatorSnapshot(
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val offer: OtaUpdateOffer? = null,
    val requestUuid: String? = null,
    val fromVersionCode: Int? = null,
    val errorMessage: String? = null,
    val lastCheckEpochMs: Long? = null,
    val serverFeatureEnabled: Boolean? = null,
    val mandatoryEnforcementEnabled: Boolean = false,
    val pendingApkPath: String? = null,
)

/** Service menu UI: no offer → no available update (do not inherit previous). */
fun AppUpdateCoordinatorSnapshot.availableUpdateForUi(): AppUpdate? = offer?.toAppUpdate()

@Serializable
data class PersistedAppUpdateState(
    val phase: String = AppUpdatePhase.Idle.name,
    val requestUuid: String? = null,
    val releaseId: String? = null,
    val fromVersionCode: Int? = null,
    val toVersionCode: Int? = null,
    val pendingApkPath: String? = null,
    val failureReason: String? = null,
    val offerJson: String? = null,
    val reportedKeys: Set<String> = emptySet(),
    val lastCheckEpochMs: Long? = null,
)
