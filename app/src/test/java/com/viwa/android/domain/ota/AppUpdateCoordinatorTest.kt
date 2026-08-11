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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.After
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
    private val clock =
        object : EpochMillisClock {
            override fun epochMillis(): Long = TelemetryIsoTimestamps.parseUtcToEpochMillis("2026-01-01T00:00:00.000Z")
        }

    @Before
    fun setup() {
        coEvery { configRepository.getJson(JsonStoreKeys.TELEMETRY_CONFIG) } returns """{"apiUrl":"https://tl.example.test"}"""
        coEvery { configRepository.get(JsonStoreKeys.OTA_MANDATORY_ENFORCEMENT) } returns "false"
        coEvery { configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) } returns null
        coEvery { configRepository.setJson(JsonStoreKeys.OTA_UPDATE_STATE, any()) } returns Unit
        coEvery { signingPolicyStore.restore() } returns Unit
        coEvery { signingPolicyStore.markTrustedManifest(any()) } returns Unit
        coEvery { tokenProvider.resolveBearerToken() } returns "jwt-test"
        every { criticalGuard.isCriticalOperationActive() } returns false
        coEvery { apkVerifier.readInstalledVersionCode() } returns 100
    }

    @After
    fun tearDown() {
        coordinatorScopes.forEach { it.cancel() }
        coordinatorScopes.clear()
        okHttpRegistry.shutdownAll()
    }

    @Test
    fun `feature off fails closed on manual check`() = runTest {
        val coordinator = createCoordinator(this)
        coordinator.onHello(appUpdatesEnabled = false, otaSigningPublicKeys = null)
        val result = coordinator.checkForUpdatesManual()
        assertTrue(result.isFailure)
    }

    @Test
    fun `manual check returns offer when update available`() = runTest {
        val coordinator = createCoordinator(this)
        val manifest = sampleManifest()
        coEvery { manifestVerifier.verifyManifest(manifest) } returns Result.success(Unit)
        coEvery {
            apiClient.checkAppUpdate("https://tl.example.test", "jwt-test", 100)
        } returns Result.success(OtaCheckResponseDto(updateAvailable = true, manifest = manifest))
        coEvery {
            apiClient.reportAppUpdate(any(), any(), any(), any(), any(), any(), OtaReportStatus.STARTED, any())
        } returns Result.success(OtaReportResponseDto("uuid", OtaReportStatus.STARTED))

        coordinator.onHello(appUpdatesEnabled = true, otaSigningPublicKeys = null)
        val result = coordinator.checkForUpdatesManual()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.versionCode == 200)
    }

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

    private fun sampleManifest(): OtaSignedManifestDto =
        OtaSignedManifestDto(
            releaseId = "11111111-1111-1111-1111-111111111111",
            versionName = "2.0.0",
            versionCode = 200,
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
