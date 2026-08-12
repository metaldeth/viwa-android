package com.viwa.android.domain.ota

import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OtaApkDownloaderTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: OtaApkDownloader

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        downloader = OtaApkDownloader(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `download does not send authorization header`() = runBlocking {
        val body = ByteArray(16) { 1 }
        val sha256 = body.sha256Hex()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))
        val destination = File(tempDir.newFolder("dl"), "ota.apk")

        downloader
            .download(
                url = server.url("/apk").toString(),
                destination = destination,
                expectedSizeBytes = body.size.toLong(),
                expectedSha256 = sha256,
            ).collect { }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    private fun ByteArray.sha256Hex(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    @Test
    fun `http 403 is terminal http exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = runDownload()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OtaHttpException)
        assertEquals(403, (result.exceptionOrNull() as OtaHttpException).statusCode)
        assertFalse(OtaBackendErrors.isTransient(result.exceptionOrNull()!!))
    }

    @Test
    fun `http 503 is transient http exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = runDownload()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OtaHttpException)
        assertTrue(OtaBackendErrors.isTransient(result.exceptionOrNull()!!))
    }

    @Test
    fun `sha mismatch is terminal integrity exception`() = runBlocking {
        val body = ByteArray(16) { 1 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val result = runDownload(expectedSizeBytes = body.size.toLong(), expectedSha256 = "b".repeat(64))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OtaDownloadIntegrityException)
        assertEquals(OtaDownloadIntegrityReason.SHA256_MISMATCH, (result.exceptionOrNull() as OtaDownloadIntegrityException).reason)
        assertFalse(OtaBackendErrors.isTransient(result.exceptionOrNull()!!))
    }

    private suspend fun runDownload(
        expectedSizeBytes: Long = 16,
        expectedSha256: String = "a".repeat(64),
    ): Result<Unit> =
        runCatching {
            val destination = File(tempDir.newFolder("dl"), "ota.apk")
            downloader
                .download(
                    url = server.url("/apk").toString(),
                    destination = destination,
                    expectedSizeBytes = expectedSizeBytes,
                    expectedSha256 = expectedSha256,
                ).collect { }
        }
}
