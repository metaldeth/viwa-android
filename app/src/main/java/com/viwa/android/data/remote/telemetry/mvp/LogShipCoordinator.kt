package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.BuildConfig
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.model.MachineRegistration
import com.viwa.android.domain.model.TelemetryConfig
import com.viwa.android.logging.AppLogFileStore
import com.viwa.android.logging.LogShipFeatureFlags
import com.viwa.android.di.AppIoScope
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber

@Singleton
class LogShipCoordinator
@Inject
constructor(
    private val apiClient: MvpTelemetryApiClient,
    private val appLogFileStore: AppLogFileStore,
    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,
    private val configRepository: ConfigRepository,
    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val syncMutex = Mutex()
    private var syncSessionGeneration = 0L
    private var helloJob: Job? = null
    private var periodicJob: Job? = null
    private var backoffAttempt = 0
    @Volatile private var serverFeatureDisabled = false
    @Volatile private var persistedCapability: MvpLogShipCapabilityDto? = null

    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun onApplicationStart() {
        if (!LogShipFeatureFlags.FEATURE_LOG_SHIP || serverFeatureDisabled) return
        val generation = ++syncSessionGeneration
        appScope.launch {
            shipLogs()
            if (generation == syncSessionGeneration) {
                schedulePeriodicSync(generation)
            }
        }
    }

    fun onHello(capability: MvpLogShipCapabilityDto?) {
        if (!LogShipFeatureFlags.FEATURE_LOG_SHIP || serverFeatureDisabled) return
        persistedCapability = capability
        val generation = ++syncSessionGeneration
        helloJob?.cancel()
        helloJob =
            appScope.launch {
                shipLogs()
                if (generation == syncSessionGeneration) {
                    schedulePeriodicSync(generation)
                }
            }
    }

    fun onNetworkValidated() {
        if (!LogShipFeatureFlags.FEATURE_LOG_SHIP || serverFeatureDisabled) return
        appScope.launch { shipLogs() }
    }

    fun onDisconnect() {
        helloJob?.cancel()
        helloJob = null
        periodicJob?.cancel()
        periodicJob = null
        backoffAttempt = 0
        appScope.launch {
            runCatching { shipLogs() }
                .onFailure { Timber.tag(TAG).w(it, "log ship on disconnect failed") }
            persistedCapability = null
            syncSessionGeneration++
        }
    }

    private fun schedulePeriodicSync(generation: Long) {
        if (generation != syncSessionGeneration) return
        periodicJob?.cancel()
        periodicJob =
            appScope.launch {
                while (isActive && generation == syncSessionGeneration) {
                    delay(resolveIntervalMs())
                    if (generation != syncSessionGeneration) break
                    if (!wsManager.isNetworkValidated()) continue
                    shipLogs()
                }
            }
    }

    private fun resolveIntervalMs(): Long {
        val seconds =
            wsManager.logShipCapability()?.syncIntervalSeconds
                ?: persistedCapability?.syncIntervalSeconds
        return if (seconds != null && seconds > 0) {
            seconds.toLong() * 1000L
        } else {
            DEFAULT_INTERVAL_MS
        }
    }

    suspend fun shipLogs() {
        if (!LogShipFeatureFlags.FEATURE_LOG_SHIP || serverFeatureDisabled) return
        if (!wsManager.isNetworkValidated()) return
        if (!isRegistered()) return
        if (!appLogFileStore.hasPendingContent()) return

        val backoffDelayMs =
            syncMutex.withLock {
                val token = bearerTokenProvider.resolveBearerToken() ?: return
                val snapshot = appLogFileStore.prepareShipSnapshot() ?: return
                val endpoint = resolveUploadEndpoint()
                apiClient
                    .uploadMachineLogs(
                        endpoint = endpoint,
                        bearerToken = token,
                        gzipBytes = snapshot.gzipBytes,
                        periodStart = snapshot.periodStart,
                        periodEnd = snapshot.periodEnd,
                        appVersionName = BuildConfig.VERSION_NAME,
                    ).fold(
                        onSuccess = {
                            appLogFileStore.commitShip(snapshot.shippedByteCount)
                            backoffAttempt = 0
                            Timber.tag(TAG).i(
                                "log ship ok bytes=%d period=%s..%s",
                                snapshot.shippedByteCount,
                                snapshot.periodStart,
                                snapshot.periodEnd,
                            )
                            null
                        },
                        onFailure = { error ->
                            if (error is LogShipFeatureDisabledException) {
                                serverFeatureDisabled = true
                                periodicJob?.cancel()
                                periodicJob = null
                                Timber.tag(TAG).i("log ship disabled by server (404)")
                                return
                            }
                            backoffAttempt++
                            val delayMs =
                                (BACKOFF_BASE_MS * backoffAttempt).coerceAtMost(BACKOFF_MAX_MS)
                            Timber.tag(TAG).w(error, "log ship failed backoffMs=%d", delayMs)
                            delayMs
                        },
                    )
            }

        if (backoffDelayMs != null) {
            delay(backoffDelayMs)
        }
    }

    private suspend fun resolveUploadEndpoint(): String {
        val capability = wsManager.logShipCapability() ?: persistedCapability
        val configured = capability?.uploadEndpoint?.trim().orEmpty()
        val baseUrl = readTelemetryConfig().apiUrl.trimEnd('/')
        if (configured.isNotBlank()) {
            return if (configured.startsWith("http", ignoreCase = true)) {
                configured
            } else {
                "$baseUrl/${configured.trimStart('/')}"
            }
        }
        return "$baseUrl/api/v1/machines/logs/upload"
    }

    private suspend fun isRegistered(): Boolean {
        val regRaw = configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.MACHINE_REGISTRATION)
        val reg =
            regRaw?.let {
                runCatching { json.decodeFromString<MachineRegistration>(it) }
                    .getOrDefault(MachineRegistration())
            } ?: MachineRegistration()
        val normalized = MachineRegistration.migrateLegacy(reg)
        return normalized.isRegistered && normalized.serialNumber.isNotBlank()
    }

    private suspend fun readTelemetryConfig(): TelemetryConfig {
        val raw = configRepository.getJson(com.viwa.android.data.local.db.JsonStoreKeys.TELEMETRY_CONFIG)
        val config =
            raw?.let {
                runCatching { json.decodeFromString<TelemetryConfig>(it) }
                    .getOrDefault(TelemetryConfig())
            } ?: TelemetryConfig()
        return TelemetryConfig.normalize(config)
    }

    companion object {
        private const val TAG = "LogShip"
        private const val DEFAULT_INTERVAL_MS = 600_000L
        private const val BACKOFF_BASE_MS = 30_000L
        private const val BACKOFF_MAX_MS = 300_000L
    }
}

/** Server has no log-ship endpoint — stop periodic uploads without treating as hard error. */
class LogShipFeatureDisabledException : Exception("log ship endpoint not available")
