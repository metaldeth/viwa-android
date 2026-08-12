package com.viwa.android.domain.ota

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.remote.ota.OtaCheckResponseDto
import com.viwa.android.data.remote.ota.OtaReleaseChannel
import com.viwa.android.data.remote.ota.OtaReportResponseDto
import com.viwa.android.data.remote.ota.OtaReportStatus
import com.viwa.android.data.remote.ota.OtaSignedManifestDto
import com.viwa.android.data.remote.telemetry.mvp.EpochMillisClock
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.test.OkHttpTestClientRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.After
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateCoordinatorTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private val okHttpRegistry = OkHttpTestClientRegistry()
    private val coordinatorScopes = mutableListOf<CoroutineScope>()

    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val tokenProvider = mockk<com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider>()
    private val apiClient = mockk<MvpTelemetryApiClient>()
    private val manifestVerifier = mockk<OtaManifestVerifier>()
    private val apkVerifier = mockk<OtaApkVerifier>()
    private val installLauncher = mockk<com.viwa.android.services.ota.OtaInstallLauncher>()
    private val criticalGuard = mockk<OtaCriticalOperationGuard>()
    private val signingKeysStore = OtaSigningKeysStore()
    private val signingPolicyStore = mockk<OtaSigningPolicyStore>(relaxed = true)
    private val persistedStateWrites = mutableListOf<String>()
    private val clock =
        object : EpochMillisClock {
            override fun epochMillis(): Long = TelemetryIsoTimestamps.parseUtcToEpochMillis("2026-01-01T00:00:00.000Z")
        }

    @Before
    fun setup() {
        coEvery { configRepository.getJson(JsonStoreKeys.TELEMETRY_CONFIG) } returns """{"apiUrl":"https://tl.example.test"}"""
        coEvery { configRepository.get(JsonStoreKeys.OTA_MANDATORY_ENFORCEMENT) } returns "false"
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns null
        coEvery { configRepository.setJson(JsonStoreKeys.OTA_UPDATE_STATE, any()) } answers {
            persistedStateWrites += secondArg<String>()
            Unit
        }
        persistedStateWrites.clear()
        coEvery { signingPolicyStore.restore() } returns Unit
        coEvery { signingPolicyStore.markTrustedManifest(any()) } returns Unit
        coEvery { tokenProvider.resolveBearerToken() } returns "jwt-test"
        every { criticalGuard.isCriticalOperationActive() } returns false
        coEvery { apkVerifier.readInstalledVersionCode() } returns 100
        every { apkVerifier.readArchiveVersionCode(any()) } returns null
    }

    @After
    fun tearDown() {
        coordinatorScopes.forEach { it.cancel() }
        coordinatorScopes.clear()
        okHttpRegistry.shutdownAll()
    }

    @Test
    fun `manual check works when hello feature off`() = runTest {
        coEvery { tokenProvider.resolveBearerToken() } returns null
        val coordinator = createCoordinator(this)
        val manifest = sampleManifest()
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))

        coordinator.onHello(appUpdatesEnabled = false, otaSigningPublicKeys = null)
        val result = coordinator.checkForUpdatesManual()

        assertTrue(result.isSuccess)
        assertEquals(200, result.getOrNull()?.versionCode)
    }

    @Test
    fun `manual check without hello reaches public check`() = runTest {
        coEvery { tokenProvider.resolveBearerToken() } returns null
        val coordinator = createCoordinator(this)
        val manifest = sampleManifest()
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))

        val result = coordinator.checkForUpdatesManual()

        assertTrue(result.isSuccess)
        assertEquals(200, result.getOrNull()?.versionCode)
    }

    @Test
    fun `manual check with bearer unavailable still reaches offer`() = runTest {
        coEvery { tokenProvider.resolveBearerToken() } returns null
        val coordinator = createCoordinator(this)
        val manifest = sampleManifest()
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))

        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        val result = coordinator.checkForUpdatesManual()

        assertTrue(result.isSuccess)
        assertEquals(AppUpdatePhase.Offered, coordinator.snapshot.value.phase)
    }

    @Test
    fun `manual check returns offer when update available`() = runTest {
        val coordinator = createCoordinator(this)
        val manifest = sampleManifest()
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))
        coEvery {
            apiClient.reportAppUpdate(any(), any(), any(), any(), any(), any(), OtaReportStatus.STARTED, any())
        } returns Result.success(OtaReportResponseDto("uuid", OtaReportStatus.STARTED))

        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        val result = coordinator.checkForUpdatesManual()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.versionCode == 200)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transient check failure keeps persisted offer`() = runTest {
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.Offered.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    releaseId = manifest.releaseId,
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.failure(OtaHttpException.fromStatus(502))

        val coordinator = createCoordinator(this)
        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        advanceUntilIdle()

        val result = coordinator.checkForUpdatesManual()
        assertTrue(result.isFailure)
        assertEquals(AppUpdatePhase.Offered, coordinator.snapshot.value.phase)
        assertEquals(200, coordinator.snapshot.value.offer?.versionCode)
        assertTrue(coordinator.snapshot.value.errorMessage.orEmpty().contains("временно недоступен"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `successful check after transient failure clears error message`() = runTest {
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.Offered.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    releaseId = manifest.releaseId,
                    failureReason = "Сервер обновлений временно недоступен. Повторим позже.",
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returnsMany
            listOf(
                Result.failure(OtaHttpException.fromStatus(502)),
                Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest)),
            )
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.reportAppUpdate(any(), any(), any(), any(), any(), any(), OtaReportStatus.STARTED, any())
        } returns Result.success(OtaReportResponseDto("uuid", OtaReportStatus.STARTED))

        val coordinator = createCoordinator(this)
        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        advanceUntilIdle()

        val failedCheck = coordinator.checkForUpdatesManual()
        assertTrue(failedCheck.isFailure)
        assertTrue(coordinator.snapshot.value.errorMessage.orEmpty().contains("временно недоступен"))

        val successCheck = coordinator.checkForUpdatesManual()
        assertTrue(successCheck.isSuccess)
        assertEquals(AppUpdatePhase.Offered, coordinator.snapshot.value.phase)
        assertNull(coordinator.snapshot.value.errorMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore clears stale failure reason when installed is newer`() = runTest {
        val manifest = sampleManifest(versionCode = 216)
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.Failed.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    pendingApkPath = "/tmp/stale.apk",
                    failureReason = "Ошибка установки",
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery { apkVerifier.readInstalledVersionCode() } returns 217

        val coordinator = createCoordinator(this)
        advanceUntilIdle()

        assertEquals(AppUpdatePhase.Idle, coordinator.snapshot.value.phase)
        assertNull(coordinator.snapshot.value.offer)
        assertNull(coordinator.snapshot.value.pendingApkPath)
        assertNull(coordinator.snapshot.value.errorMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore deletes stale pending apk only under app files dir`() = runTest {
        val filesDir = tempDir.newFolder("files")
        val staleApk = File(filesDir, "ota-update-pending.apk")
        staleApk.writeBytes(byteArrayOf(1, 2, 3))
        val manifest = sampleManifest(versionCode = 216)
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.Failed.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    pendingApkPath = staleApk.absolutePath,
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery { apkVerifier.readInstalledVersionCode() } returns 217

        val context = mockk<android.content.Context>(relaxed = true)
        every { context.packageName } returns "com.viwa.android"
        every { context.filesDir } returns filesDir
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinatorScopes += coordinatorScope
        val coordinator =
            AppUpdateCoordinator(
                context = context,
                configRepository = configRepository,
                tokenProvider = tokenProvider,
                apiClient = apiClient,
                okHttpClient = okHttpRegistry.newClient(),
                manifestVerifier = manifestVerifier,
                apkVerifier = apkVerifier,
                installLauncher = installLauncher,
                criticalOperationGuard = criticalGuard,
                signingKeysStore = signingKeysStore,
                signingPolicyStore = signingPolicyStore,
                clock = clock,
                scope = coordinatorScope,
            )
        advanceUntilIdle()

        assertFalse(staleApk.exists())
        assertEquals(AppUpdatePhase.Idle, coordinator.snapshot.value.phase)
        assertNull(coordinator.snapshot.value.offer)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore awaiting user with offer recovers to offered and allows retry`() = runTest {
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.AwaitingUser.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    releaseId = manifest.releaseId,
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson

        val coordinator = createCoordinator(this)
        advanceUntilIdle()

        assertEquals(AppUpdatePhase.Offered, coordinator.snapshot.value.phase)
        assertEquals(200, coordinator.snapshot.value.offer?.versionCode)
        assertFalse(coordinator.snapshot.value.phase.blocksDownloadOrInstall())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore awaiting user without offer recovers to idle`() = runTest {
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.AwaitingUser.name,
                    toVersionCode = 200,
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson

        val coordinator = createCoordinator(this)
        advanceUntilIdle()

        assertEquals(AppUpdatePhase.Idle, coordinator.snapshot.value.phase)
        assertNull(coordinator.snapshot.value.offer)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restore keeps safe pending apk for interrupted install`() = runTest {
        val filesDir = tempDir.newFolder("files")
        val pendingApk = File(filesDir, "ota-update-pending.apk")
        pendingApk.writeBytes(byteArrayOf(1, 2, 3))
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.Installing.name,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                    toVersionCode = manifest.versionCode,
                    pendingApkPath = pendingApk.absolutePath,
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery { apkVerifier.readInstalledVersionCode() } returns 100
        every { apkVerifier.readArchiveVersionCode(pendingApk) } returns 200

        val context = mockk<android.content.Context>(relaxed = true)
        every { context.packageName } returns "com.viwa.android"
        every { context.filesDir } returns filesDir
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinatorScopes += coordinatorScope
        val coordinator =
            AppUpdateCoordinator(
                context = context,
                configRepository = configRepository,
                tokenProvider = tokenProvider,
                apiClient = apiClient,
                okHttpClient = okHttpRegistry.newClient(),
                manifestVerifier = manifestVerifier,
                apkVerifier = apkVerifier,
                installLauncher = installLauncher,
                criticalOperationGuard = criticalGuard,
                signingKeysStore = signingKeysStore,
                signingPolicyStore = signingPolicyStore,
                clock = clock,
                scope = coordinatorScope,
            )
        advanceUntilIdle()

        assertEquals(AppUpdatePhase.Offered, coordinator.snapshot.value.phase)
        assertTrue(pendingApk.exists())
        assertEquals(pendingApk.absolutePath, coordinator.snapshot.value.pendingApkPath)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `permanent check failure http 403 transitions to failed`() = runTest {
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", 100)
        } returns Result.failure(OtaHttpException.fromStatus(403))

        val coordinator = createCoordinator(this)
        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        advanceUntilIdle()

        val result = coordinator.checkForUpdatesManual()
        assertTrue(result.isFailure)
        assertEquals(AppUpdatePhase.Failed, coordinator.snapshot.value.phase)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `success clears offer from snapshot and persisted state`() = runTest {
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.AwaitingUser.name,
                    requestUuid = "req-1",
                    releaseId = manifest.releaseId,
                    fromVersionCode = 100,
                    toVersionCode = manifest.versionCode,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery {
            apiClient.reportAppUpdate(any(), any(), any(), any(), any(), any(), OtaReportStatus.INSTALLED, any())
        } returns Result.success(OtaReportResponseDto("req-1", OtaReportStatus.INSTALLED))

        val coordinator = createCoordinator(this)
        advanceUntilIdle()
        assertEquals(200, coordinator.snapshot.value.offer?.versionCode)

        coordinator.onInstallResult(PackageInstaller.STATUS_SUCCESS, null)

        assertNull(coordinator.snapshot.value.offer)
        assertEquals(AppUpdatePhase.Success, coordinator.snapshot.value.phase)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `report failure leaves success and key unsent`() = runTest {
        val manifest = sampleManifest()
        val persistedJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }.encodeToString(
                PersistedAppUpdateState(
                    phase = AppUpdatePhase.AwaitingUser.name,
                    requestUuid = "req-1",
                    releaseId = manifest.releaseId,
                    fromVersionCode = 100,
                    toVersionCode = manifest.versionCode,
                    offerJson = Json { ignoreUnknownKeys = true }.encodeToString(OtaSignedManifestDto.serializer(), manifest),
                ),
            )
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns persistedJson
        coEvery { tokenProvider.resolveBearerToken() } returns "jwt-test"
        coEvery {
            apiClient.reportAppUpdate(any(), any(), any(), any(), any(), any(), OtaReportStatus.INSTALLED, any())
        } returns Result.failure(IOException("HTTP 503"))

        val coordinator = createCoordinator(this)
        advanceUntilIdle()
        persistedStateWrites.clear()

        coordinator.onInstallResult(PackageInstaller.STATUS_SUCCESS, null)

        assertEquals(AppUpdatePhase.Success, coordinator.snapshot.value.phase)
        val lastPersisted =
            persistedStateWrites.lastOrNull()?.let {
                Json { ignoreUnknownKeys = true }.decodeFromString<PersistedAppUpdateState>(it)
            }
        assertTrue(lastPersisted?.reportedKeys?.none { it.endsWith(":INSTALLED") } != false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `download and install proceed without bearer token`() = runTest {
        val downloadServer = MockWebServer()
        downloadServer.start()
        try {
            coEvery { tokenProvider.resolveBearerToken() } returns null
            val apkBytes = ByteArray(32) { 7 }
            val sha256 = apkBytes.sha256Hex()
            downloadServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
            val manifest =
                sampleManifest().copy(
                    sha256 = sha256,
                    fileSizeBytes = apkBytes.size.toString(),
                    downloadUrl = downloadServer.url("/apk").toString(),
                )
            coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
            every { manifestVerifier.verifyDownloadNotExpired(any()) } returns Unit
            coEvery {
                apiClient.checkAppUpdate("https://tl.example.test", 100)
            } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))
            coEvery {
                apkVerifier.verifyDownloadedApk(
                    apkFile = any(),
                    expectedPackageName = any(),
                    expectedVersionCode = any(),
                    expectedSha256 = any(),
                    expectedSizeBytes = any(),
                    expectedSigningCertSha256 = any(),
                )
            } returns Result.success(Unit)
            coEvery { installLauncher.launchInstall(any()) } returns
                com.viwa.android.services.ota.OtaInstallLaunchResult.PackageInstallerSessionStarted

            val coordinator = createCoordinator(this)
            coordinator.checkForUpdatesManual()
            val installResult = coordinator.installOfferedUpdate(requireFirmwareScope = false, hasFirmwareScope = true)
            advanceUntilIdle()

            assertTrue(installResult.isSuccess)
            assertEquals(AppUpdatePhase.AwaitingUser, coordinator.snapshot.value.phase)
            assertNull(downloadServer.takeRequest().getHeader("Authorization"))
        } finally {
            downloadServer.shutdown()
        }
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private fun createCoordinator(testScope: TestScope): AppUpdateCoordinator {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.packageName } returns "com.viwa.android"
        every { context.filesDir } returns tempDir.newFolder("files")
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinatorScopes += coordinatorScope
        return AppUpdateCoordinator(
            context = context,
            configRepository = configRepository,
            tokenProvider = tokenProvider,
            apiClient = apiClient,
            okHttpClient = okHttpRegistry.newClient(),
            manifestVerifier = manifestVerifier,
            apkVerifier = apkVerifier,
            installLauncher = installLauncher,
            criticalOperationGuard = criticalGuard,
            signingKeysStore = signingKeysStore,
            signingPolicyStore = signingPolicyStore,
            clock = clock,
            scope = coordinatorScope,
        )
    }

    private fun sampleManifest(versionCode: Int = 200): OtaSignedManifestDto =
        OtaSignedManifestDto(
            releaseId = "11111111-1111-1111-1111-111111111111",
            versionName = "2.0.0",
            versionCode = versionCode,
            channel = OtaReleaseChannel.STABLE,
            mandatory = false,
            sha256 = "a".repeat(64),
            fileSizeBytes = "1024",
            signingCertSha256 = "b".repeat(64),
            changelog = "test",
            revocationEpoch = 0,
            manifestKeyId = "k1",
            manifestSignature = "sig",
            downloadUrl = "https://example.com/apk",
            downloadExpiresAt = "2030-01-01T00:00:00Z",
        )
}
