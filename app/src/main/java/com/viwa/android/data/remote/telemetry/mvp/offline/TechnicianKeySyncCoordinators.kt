package com.viwa.android.data.remote.telemetry.mvp.offline



import com.viwa.android.data.local.technician.TechnicianAuditOutboxEntity



import com.viwa.android.data.local.technician.TechnicianAllowlistStore
import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore

import com.viwa.android.data.local.technician.TechnicianAuditOutboxStore

import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider

import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient

import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager

import com.viwa.android.domain.offline.BoundedTelemetryClock

import com.viwa.android.domain.offline.OfflineSigningKeysStore

import com.viwa.android.domain.technician.TechnicianAllowlistVerifier

import com.viwa.android.domain.technician.TechnicianKeyConstants

import com.viwa.android.domain.technician.TechnicianKeyMetrics

import com.viwa.android.domain.technician.TechnicianSessionStore

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

class TechnicianAllowlistDeltaSyncCoordinator

@Inject

constructor(

    private val apiClient: MvpTelemetryApiClient,

    private val allowlistStore: TechnicianAllowlistStore,

    private val policyStore: TechnicianKeyPolicyStore,

    private val allowlistVerifier: TechnicianAllowlistVerifier,

    private val signingKeysStore: OfflineSigningKeysStore,

    private val sessionStore: TechnicianSessionStore,

    private val clock: BoundedTelemetryClock,

    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,

    private val metrics: TechnicianKeyMetrics,

    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,

    @AppIoScope private val appScope: CoroutineScope,

) {

    private val syncMutex = Mutex()

    private var periodicJob: Job? = null

    private var backoffAttempt = 0

    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()



    fun onHello(

        capability: MvpTechnicianKeysCapabilityDto?,

        serverTechnicianKeysEnabled: Boolean?,

        serverTimeUtc: String?,

        signingPublicKeys: List<com.viwa.android.data.remote.telemetry.mvp.offline.OfflineSigningPublicKeyDto>?,

        revocationEpoch: Int?,

    ) {

        serverTimeUtc?.let { clock.updateFromServer(it) }

        if (!signingPublicKeys.isNullOrEmpty()) {

            signingKeysStore.updateFromHello(signingPublicKeys, revocationEpoch ?: 0)

        }

        revocationEpoch?.let { sessionStore.clearOnRevocationEpochChange(it) }

        appScope.launch {

            policyStore.updateFromHello(serverTechnicianKeysEnabled, capability)

        }

        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        if (!com.viwa.android.domain.technician.TechnicianKeyPolicyResolver.isFeatureEnabled(serverTechnicianKeysEnabled)) return

        if (capability == null) return

        appScope.launch {

            syncDelta(capability, reason = "hello")

            schedulePeriodicSync(capability.syncIntervalSeconds)

        }

    }



    fun onNetworkValidated(capability: MvpTechnicianKeysCapabilityDto?) {

        if (capability == null || !TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        appScope.launch { syncDelta(capability, reason = "network") }

    }



    fun onApplicationStart(capability: MvpTechnicianKeysCapabilityDto?) {

        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        appScope.launch {

            val resolved = capability ?: policyStore.persistedCapability()

            if (resolved == null) return@launch

            val policy = policyStore.read()

            if (!com.viwa.android.domain.technician.TechnicianKeyPolicyResolver.isFeatureEnabled(policy.serverTechnicianKeysEnabled)) return@launch

            syncDelta(resolved, reason = "startup")

        }

    }



    fun onDisconnect() {

        periodicJob?.cancel()

        periodicJob = null

    }



    private fun schedulePeriodicSync(intervalSeconds: Int) {

        periodicJob?.cancel()

        val intervalMs = (intervalSeconds.coerceAtLeast(60)) * 1000L

        periodicJob =

            appScope.launch {

                while (isActive) {

                    delay(intervalMs)

                    val capability =
                        wsManager.technicianKeysCapability()
                            ?: policyStore.persistedCapability()
                            ?: continue

                    if (!wsManager.isNetworkValidated()) continue

                    syncDelta(capability, reason = "periodic")

                }

            }

    }



    suspend fun syncDelta(capability: MvpTechnicianKeysCapabilityDto, reason: String) {

        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        val backoffDelayMs =

            syncMutex.withLock {

                val token = bearerTokenProvider.resolveBearerToken()

                if (token.isNullOrBlank()) {

                    Timber.tag(TAG).d("allowlist sync skip: no bearer token reason=$reason")

                    return

                }

                val cursor = allowlistStore.getCursor().ifBlank { "0" }

                apiClient

                    .fetchTechnicianAllowlistDelta(

                        endpoint = capability.allowlistDeltaEndpoint,

                        bearerToken = token,

                        cursor = cursor,

                    ).fold(

                        onSuccess = { response ->

                            clock.updateFromServer(response.serverTimeUtc)

                            sessionStore.clearOnRevocationEpochChange(response.revocationEpoch)

                            val verifiedRecords = response.records.filter { allowlistVerifier.verifyRecord(it) }

                            val rejected = response.records.size - verifiedRecords.size

                            if (rejected > 0) {

                                Timber.tag(TAG).w("allowlist sync rejected %d invalid signatures", rejected)

                            }

                            allowlistStore.applyDeltaTransactionally(

                                records = verifiedRecords,

                                tombstones = response.tombstones,

                                nextCursor = response.nextCursor,

                                revocationEpoch = response.revocationEpoch,

                            )

                            policyStore.markTrustedAllowlistSync(

                                revocationEpoch = response.revocationEpoch,

                            )

                            policyStore.updateFromHello(

                                serverTechnicianKeysEnabled = null,

                                capability = capability,

                            )

                            backoffAttempt = 0

                            metrics.logDepthSnapshot()

                            Timber.tag(TAG).i(

                                "allowlist sync ok reason=%s records=%d tombstones=%d cursor=%s",

                                reason,

                                verifiedRecords.size,

                                response.tombstones.size,

                                response.nextCursor,

                            )

                            null

                        },

                        onFailure = { error ->

                            backoffAttempt++

                            val delayMs =

                                (TechnicianKeyConstants.ALLOWLIST_SYNC_BACKOFF_BASE_MS * backoffAttempt)

                                    .coerceAtMost(TechnicianKeyConstants.ALLOWLIST_SYNC_BACKOFF_MAX_MS)

                            Timber.tag(TAG).w(error, "allowlist sync failed reason=%s backoffMs=%d", reason, delayMs)

                            delayMs

                        },

                    )

            }

        if (backoffDelayMs != null) {

            delay(backoffDelayMs)

        }

    }



    companion object {

        private const val TAG = "TechAllowlistSync"

    }

}



@Singleton

class TechnicianAuditSyncCoordinator

@Inject

constructor(

    private val apiClient: MvpTelemetryApiClient,

    private val auditOutboxStore: TechnicianAuditOutboxStore,

    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,

    private val metrics: TechnicianKeyMetrics,

    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,

    @AppIoScope private val appScope: CoroutineScope,

) {

    private val syncMutex = Mutex()

    private var periodicJob: Job? = null

    private var backoffAttempt = 0

    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()



    fun onHello(capability: MvpTechnicianKeysCapabilityDto?) {

        if (capability == null || !TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        appScope.launch {

            syncBatch(capability)

            schedulePeriodicSync()

        }

    }



    fun onNetworkValidated(capability: MvpTechnicianKeysCapabilityDto?) {

        if (capability == null || !TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        appScope.launch { syncBatch(capability) }

    }



    fun onDisconnect() {

        periodicJob?.cancel()

        periodicJob = null

        backoffAttempt = 0

    }



    private fun schedulePeriodicSync() {

        periodicJob?.cancel()

        periodicJob =

            appScope.launch {

                while (isActive) {

                    delay(TechnicianKeyConstants.AUDIT_SYNC_INTERVAL_MS)

                    val capability = wsManager.technicianKeysCapability() ?: continue

                    if (!wsManager.isNetworkValidated()) continue

                    syncBatch(capability)

                }

            }

    }



    suspend fun syncBatch(capability: MvpTechnicianKeysCapabilityDto) {

        if (!TechnicianKeyFeatureFlags.FEATURE_TECHNICIAN_KEYS) return

        val backoffDelayMs =

            syncMutex.withLock {

                val token = bearerTokenProvider.resolveBearerToken() ?: return

                val pending = auditOutboxStore.listPending(TechnicianKeyConstants.MAX_AUDIT_BATCH_SIZE)

                if (pending.isEmpty()) return

                val request =

                    TechnicianAuditBatchRequestDto(

                        items =

                            pending.map {

                                TechnicianAuditBatchItemDto(

                                    requestUuid = it.requestUuid,

                                    fingerprint = it.fingerprint,

                                    action = it.action,

                                    channel = it.channel,

                                    outcome = it.outcome,

                                    failureCode = it.failureCode,

                                )

                            },

                    )

                apiClient

                    .submitTechnicianAuditBatch(

                        endpoint = capability.auditBatchEndpoint,

                        bearerToken = token,

                        request = request,

                    ).fold(

                        onSuccess = { response ->

                            response.results.forEach { result ->

                                val requestUuid = result.requestUuid

                                if (requestUuid.isBlank()) {

                                    Timber.tag(TAG).w("audit batch result missing requestUuid — leaving pending")

                                    return@forEach

                                }

                                when (result.status) {

                                    "ACCEPTED", "IDEMPOTENT" ->

                                        auditOutboxStore.markSynced(

                                            requestUuid,

                                            TechnicianAuditOutboxEntity.SYNC_SYNCED,

                                        )

                                    "REJECTED" ->

                                        auditOutboxStore.markSynced(

                                            requestUuid,

                                            TechnicianAuditOutboxEntity.SYNC_REJECTED,

                                        )

                                }

                            }

                            auditOutboxStore.purgeTerminalOlderThan(

                                TechnicianKeyConstants.AUDIT_TERMINAL_RETENTION_MS,

                            )

                            backoffAttempt = 0

                            metrics.logDepthSnapshot()

                            Timber.tag(TAG).i("audit batch synced items=%d", pending.size)

                            null

                        },

                        onFailure = { error ->

                            backoffAttempt++

                            val delayMs =

                                (TechnicianKeyConstants.AUDIT_SYNC_BACKOFF_BASE_MS * backoffAttempt)

                                    .coerceAtMost(TechnicianKeyConstants.AUDIT_SYNC_BACKOFF_MAX_MS)

                            Timber.tag(TAG).w(error, "audit batch sync failed pending=%d backoffMs=%d", pending.size, delayMs)

                            delayMs

                        },

                    )

            }

        if (backoffDelayMs != null) {

            delay(backoffDelayMs)

        }

    }



    companion object {

        private const val TAG = "TechAuditSync"

    }

}



@Singleton

class TechnicianKeySessionCoordinator

@Inject

constructor(

    private val deltaSyncCoordinator: TechnicianAllowlistDeltaSyncCoordinator,

    private val auditSyncCoordinator: TechnicianAuditSyncCoordinator,

    private val policyStore: TechnicianKeyPolicyStore,

    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,

    @AppIoScope private val appScope: CoroutineScope,

) {

    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()



    fun onApplicationStart() {

        appScope.launch {

            val capability =

                wsManager.technicianKeysCapability()

                    ?: policyStore.persistedCapability()

            deltaSyncCoordinator.onApplicationStart(capability)

        }

    }



    fun onHello(hello: com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto) {

        val serverEnabled = hello.featureFlags?.technicianKeys

        val capability = hello.capabilities?.technicianKeys

        deltaSyncCoordinator.onHello(

            capability = capability,

            serverTechnicianKeysEnabled = serverEnabled,

            serverTimeUtc = hello.serverTimeUtc,

            signingPublicKeys = hello.signingPublicKeys,

            revocationEpoch = hello.revocationEpoch,

        )

        if (com.viwa.android.domain.technician.TechnicianKeyPolicyResolver.isFeatureEnabled(serverEnabled)) {

            auditSyncCoordinator.onHello(capability)

        }

    }



    fun onNetworkValidated() {

        appScope.launch {

            val capability = wsManager.technicianKeysCapability() ?: policyStore.persistedCapability()

            deltaSyncCoordinator.onNetworkValidated(capability)

            capability?.let { auditSyncCoordinator.syncBatch(it) }

        }

    }



    fun onDisconnect() {

        deltaSyncCoordinator.onDisconnect()

        auditSyncCoordinator.onDisconnect()

    }

}


