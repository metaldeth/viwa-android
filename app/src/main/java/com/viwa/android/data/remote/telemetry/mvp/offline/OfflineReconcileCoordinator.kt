package com.viwa.android.data.remote.telemetry.mvp.offline

import com.viwa.android.data.local.entitlement.OfflineUsageLedgerStore
import com.viwa.android.data.remote.telemetry.mvp.MachineOutboxBearerTokenProvider
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryApiClient
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import com.viwa.android.domain.offline.OfflineEntitlementMetrics
import com.viwa.android.domain.offline.OfflinePourTransactionCoordinator
import com.viwa.android.di.AppIoScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class OfflineReconcileCoordinator
@Inject
constructor(
    private val apiClient: MvpTelemetryApiClient,
    private val ledgerStore: OfflineUsageLedgerStore,
    private val pourCoordinator: OfflinePourTransactionCoordinator,
    private val bearerTokenProvider: MachineOutboxBearerTokenProvider,
    private val metrics: OfflineEntitlementMetrics,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val reconcileMutex = Mutex()

    suspend fun onHello(capability: MvpOfflineEntitlementCapabilityDto?) {
        if (!OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) return
        if (capability == null) return
        reconcileBatch(capability)
    }

    suspend fun reconcileBatch(capability: MvpOfflineEntitlementCapabilityDto) {
        if (!OfflineEntitlementFeatureFlags.FEATURE_OFFLINE_ENTITLEMENT) return
        reconcileMutex.withLock {
            val token = bearerTokenProvider.resolveBearerToken()
            if (token.isNullOrBlank()) {
                Timber.tag(TAG).d("reconcile skip: no bearer token")
                return
            }
            val awaiting = ledgerStore.listAwaitingReconcile(capability.maxReconcileBatchSize)
            if (awaiting.isEmpty()) return

            val items =
                awaiting.map { row ->
                    OfflineReconcileBatchItemDto(
                        requestUuid = row.requestUuid,
                        grantId = row.grantId,
                        soldAt = TelemetryIsoTimestamps.fromEpochMillis(row.soldAtMs),
                        volumeMl = row.finalizedVolumeMl ?: row.requestedVolumeMl,
                        saleId = row.saleId,
                        drinkId = row.drinkId,
                    )
                }
            apiClient
                .submitOfflineReconcileBatch(
                    endpoint = capability.reconcileBatchEndpoint,
                    bearerToken = token,
                    request = OfflineReconcileBatchRequestDto(items = items),
                ).onSuccess { response ->
                    response.results.forEach { result ->
                        val row = awaiting.firstOrNull { it.requestUuid == result.requestUuid } ?: return@forEach
                        pourCoordinator.applyReconcileResult(
                            requestUuid = result.requestUuid,
                            status = result.status,
                            code = result.code,
                            message = result.message,
                            subjectHash = row.subjectHash,
                            machineId = row.machineId,
                        )
                        if (result.status.equals("ACCEPTED", ignoreCase = true) ||
                            result.status.equals("IDEMPOTENT", ignoreCase = true)
                        ) {
                            Timber.tag(TAG).i("reconcile ok requestUuid=%s status=%s", result.requestUuid, result.status)
                        } else {
                            Timber.tag(TAG).w(
                                "reconcile audit requestUuid=%s status=%s code=%s",
                                result.requestUuid,
                                result.status,
                                result.code,
                            )
                        }
                    }
                    metrics.logDepthSnapshot()
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "reconcile batch failed")
                }
        }
    }

    companion object {
        private const val TAG = "OfflineReconcile"
    }
}
