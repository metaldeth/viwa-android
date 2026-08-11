package com.viwa.android.domain.ota

import android.content.Context
import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.remote.ota.OtaCheckResponseDto
import com.viwa.android.data.remote.ota.OtaReportStatus
import com.viwa.android.data.remote.ota.OtaSignedManifestDto
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.domain.model.UpdateProgress
import com.viwa.android.data.remote.telemetry.mvp.EpochMillisClock
import com.viwa.android.services.ota.OtaInstallLaunchResult
import com.viwa.android.services.ota.OtaInstallLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber

@Singleton
class AppUpdateCoordinator
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val tokenProvider: MachineOutboxBearerTokenProvider,
    private val apiClient: MvpTelemetryApiClient,
    private val okHttpClient: OkHttpClient,
    private val manifestVerifier: OtaManifestVerifier,
    private val apkVerifier: OtaApkVerifier,
    private val installLauncher: OtaInstallLauncher,
    private val criticalOperationGuard: OtaCriticalOperationGuard,
    private val signingKeysStore: OtaSigningKeysStore,
    private val signingPolicyStore: OtaSigningPolicyStore,
    private val clock: EpochMillisClock,
    @AppIoScope private val scope: CoroutineScope,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val mutex = Mutex()
    private val downloader = OtaApkDownloader(okHttpClient)

    private val _snapshot = MutableStateFlow(AppUpdateCoordinatorSnapshot())
    val snapshot: StateFlow<AppUpdateCoordinatorSnapshot> = _snapshot.asStateFlow()

    private val _progressFlow = MutableSharedFlow<UpdateProgress>(replay = 1)
    val progressFlow: SharedFlow<UpdateProgress> = _progressFlow.asSharedFlow()

    @Volatile
    private var serverAppUpdatesEnabled: Boolean? = null

    private var periodicJob: Job? = null
    private var persisted = PersistedAppUpdateState()
    private var retryAttempt = 0

    init {
        scope.launch {
            signingPolicyStore.restore()
            restorePersistedState()
            startPeriodicChecksIfNeeded()
        }
    }

    suspend fun emitProgress(progress: UpdateProgress) {
        _progressFlow.emit(progress)
    }

    fun onHello(
        appUpdatesEnabled: Boolean?,
        otaSigningPublicKeys: List<OfflineSigningPublicKeyDto>? = null,
    ) {
        serverAppUpdatesEnabled = appUpdatesEnabled
        signingKeysStore.updateFromHello(otaSigningPublicKeys)
        _snapshot.value = _snapshot.value.copy(serverFeatureEnabled = appUpdatesEnabled)
        if (appUpdatesEnabled != true) {
            cancelPendingAutoWork()
        } else {
            scope.launch { startPeriodicChecksIfNeeded() }
        }
    }

    suspend fun checkForUpdatesManual(): Result<OtaUpdateOffer?> =
        mutex.withLock {
            if (!isTelemetryCheckAllowed()) {
                return Result.failure(IllegalStateException("OTA telemetry недоступен"))
            }
            performCheck(isManual = true)
        }

    suspend fun installOfferedUpdate(requireFirmwareScope: Boolean, hasFirmwareScope: Boolean): Result<Unit> =
        mutex.withLock {
            if (requireFirmwareScope && !hasFirmwareScope) {
                return Result.failure(SecurityException("Требуется scope firmware.update"))
            }
            val offer = _snapshot.value.offer ?: return Result.failure(IllegalStateException("Нет доступного обновления"))
            if (criticalOperationGuard.isCriticalOperationActive()) {
                return Result.failure(IllegalStateException("Обновление недоступно во время налива или оплаты"))
            }
            downloadVerifyAndInstall(offer)
        }

    suspend fun onInstallResult(status: Int, message: String?) {
        mutex.withLock {
            val ok = status == android.content.pm.PackageInstaller.STATUS_SUCCESS
            if (ok) {
                transition(AppUpdatePhase.Success, failureReason = null)
                reportCurrent(OtaReportStatus.INSTALLED)
            } else {
                transition(AppUpdatePhase.Failed, failureReason = message ?: "Install failed")
                reportCurrent(OtaReportStatus.FAILED, message)
            }
            clearPendingApk()
        }
    }

    suspend fun setMandatoryEnforcementEnabled(enabled: Boolean) {
        configRepository.set(JsonStoreKeys.OTA_MANDATORY_ENFORCEMENT, if (enabled) "true" else "false")
        _snapshot.value = _snapshot.value.copy(mandatoryEnforcementEnabled = enabled)
    }

    private suspend fun performCheck(isManual: Boolean): Result<OtaUpdateOffer?> {
        if (!isManual && criticalOperationGuard.isCriticalOperationActive()) {
            return Result.success(null)
        }
        transition(AppUpdatePhase.Checking)
        val fromVersionCode = apkVerifier.readInstalledVersionCode()
        val bearer = tokenProvider.resolveBearerToken()
        if (bearer.isNullOrBlank()) {
            transition(AppUpdatePhase.Failed, failureReason = "Machine JWT недоступен")
            return Result.failure(IllegalStateException("Machine JWT unavailable"))
        }
        val apiUrl = readTelemetryApiUrl()
        return apiClient
            .checkAppUpdate(apiUrl, bearer, fromVersionCode)
            .fold(
                onSuccess = { response ->
                    retryAttempt = 0
                    persisted = persisted.copy(lastCheckEpochMs = clock.epochMillis())
                    handleCheckResponse(response, fromVersionCode)
                },
                onFailure = { error ->
                    scheduleRetryIfNeeded(error)
                    transition(AppUpdatePhase.Failed, failureReason = error.message)
                    Result.failure(error)
                },
            )
    }

    private suspend fun handleCheckResponse(
        response: OtaCheckResponseDto,
        fromVersionCode: Int,
    ): Result<OtaUpdateOffer?> {
        if (!response.updateAvailable || response.manifest == null) {
            transition(AppUpdatePhase.Idle, offer = null)
            return Result.success(null)
        }
        manifestVerifier.verifyManifest(response.manifest).onFailure { error ->
            transition(AppUpdatePhase.Failed, failureReason = error.message)
            reportFailure(response.manifest, fromVersionCode, error.message)
            return Result.failure(error)
        }
        signingPolicyStore.markTrustedManifest(response.manifest.revocationEpoch ?: 0)
        val offer = OtaUpdateOffer.fromManifest(response.manifest)
        if (offer.versionCode <= fromVersionCode) {
            transition(AppUpdatePhase.Idle, offer = null)
            return Result.success(null)
        }
        val requestUuid = persisted.requestUuid ?: UUID.randomUUID().toString()
        persisted =
            persisted.copy(
                requestUuid = requestUuid,
                releaseId = offer.releaseId,
                fromVersionCode = fromVersionCode,
                toVersionCode = offer.versionCode,
                offerJson = json.encodeToString(OtaSignedManifestDto.serializer(), response.manifest),
            )
        transition(AppUpdatePhase.Offered, offer = offer, requestUuid = requestUuid, fromVersionCode = fromVersionCode)
        reportOnce(OtaReportStatus.STARTED)
        if (shouldAutoInstall(offer)) {
            downloadVerifyAndInstall(offer)
            return Result.success(offer)
        }
        return Result.success(offer)
    }

    private suspend fun downloadVerifyAndInstall(offer: OtaUpdateOffer): Result<Unit> {
        if (criticalOperationGuard.isCriticalOperationActive()) {
            return Result.failure(IllegalStateException("Критическая операция активна"))
        }
        transition(AppUpdatePhase.Downloading, offer = offer)
        reportOnce(OtaReportStatus.DOWNLOADING)
        val apkFile = pendingApkFile()
        return runCatching {
            manifestVerifier.verifyDownloadNotExpired(offer.downloadExpiresAt)
            downloader.download(
                url = offer.downloadUrl,
                destination = apkFile,
                expectedSizeBytes = offer.fileSizeBytes,
                expectedSha256 = offer.sha256,
            ).collect { progress ->
                _progressFlow.emit(progress)
            }
            transition(AppUpdatePhase.Verifying, offer = offer, pendingApkPath = apkFile.absolutePath)
            apkVerifier.verifyDownloadedApk(
                apkFile = apkFile,
                expectedPackageName = context.packageName,
                expectedVersionCode = offer.versionCode,
                expectedSha256 = offer.sha256,
                expectedSizeBytes = offer.fileSizeBytes,
                expectedSigningCertSha256 = offer.signingCertSha256,
            ).getOrThrow()
            reportOnce(OtaReportStatus.DOWNLOADED)
            launchInstall(apkFile, offer)
        }.onFailure { error ->
            downloader.deletePartialFiles(apkFile)
            transition(AppUpdatePhase.Failed, failureReason = error.message, offer = offer)
            reportOnce(OtaReportStatus.FAILED, error.message)
        }
    }

    private suspend fun launchInstall(apkFile: File, offer: OtaUpdateOffer) {
        transition(AppUpdatePhase.Installing, offer = offer, pendingApkPath = apkFile.absolutePath)
        reportOnce(OtaReportStatus.INSTALLING)
        when (val result = installLauncher.launchInstall(apkFile)) {
            is OtaInstallLaunchResult.PackageInstallerSessionStarted -> {
                transition(AppUpdatePhase.AwaitingUser, offer = offer)
            }
            is OtaInstallLaunchResult.ActionViewFallbackStarted -> {
                transition(AppUpdatePhase.AwaitingUser, offer = offer)
            }
            is OtaInstallLaunchResult.Failed -> {
                transition(AppUpdatePhase.Failed, failureReason = result.reason, offer = offer)
                reportOnce(OtaReportStatus.FAILED, result.reason)
            }
        }
    }

    private suspend fun shouldAutoInstall(offer: OtaUpdateOffer): Boolean {
        val enforcement = configRepository.get(JsonStoreKeys.OTA_MANDATORY_ENFORCEMENT) == "true"
        return enforcement && offer.mandatory
    }

    private fun isTelemetryCheckAllowed(): Boolean {
        if (serverAppUpdatesEnabled != true) return false
        return true
    }

    private fun cancelPendingAutoWork() {
        periodicJob?.cancel()
        periodicJob = null
        if (_snapshot.value.phase == AppUpdatePhase.Checking) {
            _snapshot.value = _snapshot.value.copy(phase = AppUpdatePhase.Idle)
        }
    }

    private fun startPeriodicChecksIfNeeded() {
        if (serverAppUpdatesEnabled != true) return
        if (periodicJob?.isActive == true) return
        periodicJob =
            scope.launch {
                while (true) {
                    delay(OtaConstants.AUTO_CHECK_INTERVAL_MS)
                    if (serverAppUpdatesEnabled != true) continue
                    if (criticalOperationGuard.isCriticalOperationActive()) continue
                    mutex.withLock {
                        performCheck(isManual = false)
                    }
                }
            }
    }

    private suspend fun scheduleRetryIfNeeded(error: Throwable) {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) return
        retryAttempt++
        val backoffMs = min(30 * 60_000L, (1L shl retryAttempt) * 30_000L)
        scope.launch {
            delay(backoffMs)
            if (serverAppUpdatesEnabled == true && !criticalOperationGuard.isCriticalOperationActive()) {
                mutex.withLock { performCheck(isManual = false) }
            }
        }
    }

    private suspend fun restorePersistedState() {
        val raw = configRepository.getJson(JsonStoreKeys.OTA_UPDATE_STATE) ?: return
        persisted = runCatching { json.decodeFromString<PersistedAppUpdateState>(raw) }.getOrDefault(PersistedAppUpdateState())
        val offer =
            persisted.offerJson?.let {
                runCatching {
                    OtaUpdateOffer.fromManifest(json.decodeFromString(OtaSignedManifestDto.serializer(), it))
                }.getOrNull()
            }
        val phase = runCatching { AppUpdatePhase.valueOf(persisted.phase) }.getOrDefault(AppUpdatePhase.Idle)
        _snapshot.value =
            AppUpdateCoordinatorSnapshot(
                phase = phase,
                offer = offer,
                requestUuid = persisted.requestUuid,
                fromVersionCode = persisted.fromVersionCode,
                errorMessage = persisted.failureReason,
                lastCheckEpochMs = persisted.lastCheckEpochMs,
                mandatoryEnforcementEnabled = configRepository.get(JsonStoreKeys.OTA_MANDATORY_ENFORCEMENT) == "true",
                pendingApkPath = persisted.pendingApkPath,
            )
    }

    private suspend fun persistState() {
        configRepository.setJson(JsonStoreKeys.OTA_UPDATE_STATE, json.encodeToString(persisted))
    }

    private suspend fun transition(
        phase: AppUpdatePhase,
        offer: OtaUpdateOffer? = _snapshot.value.offer,
        requestUuid: String? = _snapshot.value.requestUuid,
        fromVersionCode: Int? = _snapshot.value.fromVersionCode,
        failureReason: String? = _snapshot.value.errorMessage,
        pendingApkPath: String? = _snapshot.value.pendingApkPath,
    ) {
        persisted =
            persisted.copy(
                phase = phase.name,
                failureReason = failureReason,
                pendingApkPath = pendingApkPath,
                requestUuid = requestUuid ?: persisted.requestUuid,
                fromVersionCode = fromVersionCode ?: persisted.fromVersionCode,
                toVersionCode = offer?.versionCode ?: persisted.toVersionCode,
                releaseId = offer?.releaseId ?: persisted.releaseId,
                offerJson =
                    offer?.let { currentOffer ->
                        persisted.offerJson?.let { existing ->
                            existing
                        } ?: run {
                            // keep existing serialized manifest when possible
                            persisted.offerJson
                        }
                    },
            )
        _snapshot.value =
            _snapshot.value.copy(
                phase = phase,
                offer = offer,
                requestUuid = requestUuid ?: _snapshot.value.requestUuid,
                fromVersionCode = fromVersionCode ?: _snapshot.value.fromVersionCode,
                errorMessage = failureReason,
                pendingApkPath = pendingApkPath,
            )
        persistState()
    }

    private suspend fun reportOnce(status: OtaReportStatus, failureReason: String? = null) {
        val key = "${persisted.requestUuid}:${status.name}"
        if (persisted.reportedKeys.contains(key)) return
        reportCurrent(status, failureReason)
        persisted = persisted.copy(reportedKeys = persisted.reportedKeys + key)
        persistState()
    }

    private suspend fun reportCurrent(status: OtaReportStatus, failureReason: String? = null) {
        val requestUuid = persisted.requestUuid ?: return
        val releaseId = persisted.releaseId ?: return
        val toVersion = persisted.toVersionCode ?: return
        val bearer = tokenProvider.resolveBearerToken() ?: return
        val apiUrl = readTelemetryApiUrl()
        apiClient.reportAppUpdate(
            apiUrl,
            bearer,
            requestUuid,
            releaseId,
            persisted.fromVersionCode,
            toVersion,
            status,
            failureReason,
        )
    }

    private suspend fun reportFailure(
        manifest: OtaSignedManifestDto,
        fromVersionCode: Int,
        failureReason: String?,
    ) {
        val requestUuid = persisted.requestUuid ?: UUID.randomUUID().toString()
        val bearer = tokenProvider.resolveBearerToken() ?: return
        apiClient.reportAppUpdate(
            readTelemetryApiUrl(),
            bearer,
            requestUuid,
            manifest.releaseId,
            fromVersionCode,
            manifest.versionCode,
            OtaReportStatus.FAILED,
            failureReason,
        )
    }

    private suspend fun readTelemetryApiUrl(): String {
        val raw = configRepository.getJson(JsonStoreKeys.TELEMETRY_CONFIG)
        val config =
            raw?.let {
                runCatching { json.decodeFromString<TelemetryConfig>(it) }.getOrNull()
            } ?: TelemetryConfig()
        return config.apiUrl
    }

    private fun pendingApkFile(): File = File(context.filesDir, OtaConstants.TEMP_APK_NAME)

    private suspend fun clearPendingApk() {
        val file = pendingApkFile()
        downloader.deletePartialFiles(file)
        persisted = persisted.copy(pendingApkPath = null)
        persistState()
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}
