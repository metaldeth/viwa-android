package com.viwa.android.data.remote.telemetry.mvp.offline

import com.viwa.android.data.remote.telemetry.mvp.MvpHelloPayloadDto
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.domain.offline.OfflinePourTransactionCoordinator
import com.viwa.android.di.AppIoScope
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Singleton
class OfflineEntitlementSessionCoordinator
@Inject
constructor(
    private val deltaSyncCoordinator: OfflineGrantsDeltaSyncCoordinator,
    private val reconcileCoordinator: OfflineReconcileCoordinator,
    private val pourCoordinator: OfflinePourTransactionCoordinator,
    private val wsManagerLazy: Lazy<MvpTelemetryWebSocketManager>,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val wsManager: MvpTelemetryWebSocketManager get() = wsManagerLazy.get()

    fun onApplicationStart() {
        appScope.launch {
            pourCoordinator.recoverUncertainStatesOnStartup()
        }
    }

    fun onHello(hello: MvpHelloPayloadDto) {
        deltaSyncCoordinator.onHello(
            capability = hello.capabilities?.offlineEntitlement,
            serverTimeUtc = hello.serverTimeUtc,
            signingPublicKeys = hello.signingPublicKeys,
            revocationEpoch = hello.revocationEpoch,
        )
        appScope.launch {
            reconcileCoordinator.onHello(hello.capabilities?.offlineEntitlement)
        }
    }

    fun onNetworkValidated() {
        val capability = wsManager.offlineEntitlementCapability()
        deltaSyncCoordinator.onNetworkValidated(capability)
        appScope.launch {
            capability?.let { reconcileCoordinator.reconcileBatch(it) }
        }
    }
}
