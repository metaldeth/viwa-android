package com.viwa.android.data.remote.telemetry.mvp.offline

import com.viwa.android.data.local.db.JsonStoreKeys
import com.viwa.android.data.local.entitlement.EntitlementCacheStore
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.domain.offline.BoundedTelemetryClock
import com.viwa.android.domain.offline.OfflineEntitlementConstants
import com.viwa.android.domain.offline.OfflineEntitlementMetrics
import com.viwa.android.domain.offline.OfflineGrantVerifier
import com.viwa.android.domain.offline.OfflineSigningKeysStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
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
import timber.log.Timber

@Singleton
class OfflineGrantsDeltaSyncCoordinator
@Inject
constructor(
    private val apiClient: MvpTelemetryApiClient,
    private val cacheStore: EntitlementCacheStore,
    private val grantVerifier: OfflineGrantVerifier,
    private val signingKeysStore: OfflineSigningKeysStore,
    private val clock: BoundedTelemetryClock,
    private val configRepository: ConfigRepository,
    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,
    private val metrics: OfflineEntitlementMetrics,
    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val syncMutex = Mutex()
    private var periodicJob: Job? = null
    private var backoffAttempt = 0
    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()

    fun onHello(
        capability: MvpOfflineEntitlementCapabilityDto?,
        serverTimeUtc: String?,
        signingPublicKeys: List<OfflineSigningPublicKeyDto>?,
        revocationEpoch: Int?,
    ) {
        serverTimeUtc?.let { clock.updateFromServer(it) }
        if (!signingPublicKeys.isNullOrEmpty()) {
            signingKeysStore.updateFromHello(signingPublicKeys, revocationEpoch ?: 0)
        }
        if (!OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) return
        if (capability == null) return
        appScope.launch {
            syncDelta(capability, reason = "hello")
            schedulePeriodicSync(capability)
        }
    }

    fun onNetworkValidated(capability: MvpOfflineEntitlementCapabilityDto?) {
        if (capability == null || !OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) return
        appScope.launch { syncDelta(capability, reason = "network") }
    }

    private fun schedulePeriodicSync(capability: MvpOfflineEntitlementCapabilityDto) {
        periodicJob?.cancel()
        periodicJob =
            appScope.launch {
                while (isActive) {
                    delay(OfflineEntitlementConstants.DELTA_SYNC_INTERVAL_MS)
                    if (!wsManager.isNetworkValidated()) continue
                    syncDelta(capability, reason = "periodic-15m")
                }
            }
    }

    suspend fun syncDelta(capability: MvpOfflineEntitlementCapabilityDto, reason: String) {
        if (!OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) return
        syncMutex.withLock {
            val token = bearerTokenProvider.resolveBearerToken()
            if (token.isNullOrBlank()) {
                Timber.tag(TAG).d("delta sync skip: no bearer token reason=$reason")
                return
            }
            val cursor = configRepository.get(JsonStoreKeys.OFFLINE_GRANTS_DELTA_CURSOR).orEmpty().ifBlank { "0" }
            apiClient
                .fetchOfflineGrantsDelta(
                    endpoint = capability.grantsDeltaEndpoint,
                    bearerToken = token,
                    cursor = cursor,
                ).onSuccess { response ->
                    clock.updateFromServer(response.serverTimeUtc)
                    var applied = 0
                    var tombstones = 0
                    response.grants.forEach { grant ->
                        if (grantVerifier.verifyGrantPayload(grant)) {
                            cacheStore.upsertGrant(grant)
                            applied++
                        } else {
                            Timber.tag(TAG).w("delta grant verify failed grantId=%s", grant.grantId)
                        }
                    }
                    response.tombstones.forEach { tomb ->
                        cacheStore.applyTombstone(tomb.grantId)
                        tombstones++
                    }
                    configRepository.set(JsonStoreKeys.OFFLINE_GRANTS_DELTA_CURSOR, response.nextCursor)
                    backoffAttempt = 0
                    metrics.logDepthSnapshot()
                    Timber.tag(TAG).i(
                        "delta sync ok reason=%s grants=%d tombstones=%d cursor=%s",
                        reason,
                        applied,
                        tombstones,
                        response.nextCursor,
                    )
                }.onFailure { error ->
                    backoffAttempt++
                    val delayMs =
                        (OfflineEntitlementConstants.DELTA_SYNC_BACKOFF_BASE_MS * backoffAttempt)
                            .coerceAtMost(OfflineEntitlementConstants.DELTA_SYNC_BACKOFF_MAX_MS)
                    Timber.tag(TAG).w(error, "delta sync failed reason=%s backoffMs=%d", reason, delayMs)
                    delay(delayMs)
                }
        }
    }

    companion object {
        private const val TAG = "OfflineGrantsDelta"
    }
}
