package com.viwa.android.domain.ota

import com.viwa.android.domain.model.UpdateProgress
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request

class OtaApkDownloader(
    private val okHttpClient: OkHttpClient,
) {
    fun download(
        url: String,
        destination: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        bearerToken: String,
        maxBytes: Long = OtaConstants.MAX_APK_BYTES,
    ): Flow<UpdateProgress> =
        flow {
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
            val tempFile = File(destination.parentFile, "${destination.name}.part")
            if (tempFile.exists()) tempFile.delete()

            // Server requires machine JWT + HMAC query token (see app-updates download guard).
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $bearerToken")
                    .get()
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    tempFile.delete()
                    throw IOException("Download HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty download body")
                val totalBytes =
                    when {
                        expectedSizeBytes > 0 -> expectedSizeBytes
                        body.contentLength() > 0 -> body.contentLength()
                        else -> -1L
                    }
                if (expectedSizeBytes > 0 && body.contentLength() > 0 && body.contentLength() != expectedSizeBytes) {
                    throw IOException("Unexpected Content-Length")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloaded += read
                            if (downloaded > maxBytes) {
                                tempFile.delete()
                                throw IOException("APK exceeds max size")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            emit(UpdateProgress(downloaded, totalBytes.coerceAtLeast(0)))
                        }
                    }
                }

                if (expectedSizeBytes > 0 && downloaded != expectedSizeBytes) {
                    tempFile.delete()
                    throw IOException("Downloaded size mismatch")
                }
                val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hashHex.equals(expectedSha256, ignoreCase = true)) {
                    tempFile.delete()
                    throw IOException("Downloaded SHA-256 mismatch")
                }
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
            }
        }

    fun deletePartialFiles(destination: File) {
        File(destination.parentFile, "${destination.name}.part").delete()
        destination.delete()
    }
}
