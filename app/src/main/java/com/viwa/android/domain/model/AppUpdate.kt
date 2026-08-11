package com.viwa.android.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdate(
    val version: String,
    val url: String,
    val changelog: String = "",
    val versionCode: Int? = null,
    val channel: String? = null,
    val releaseId: String? = null,
)

data class UpdateProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val progress: Float
        get() =
            if (totalBytes > 0) {
                bytesDownloaded.toFloat() / totalBytes
            } else {
                0f
            }
}
