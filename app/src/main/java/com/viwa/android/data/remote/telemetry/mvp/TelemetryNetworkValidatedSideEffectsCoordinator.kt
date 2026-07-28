package com.viwa.android.data.remote.telemetry.mvp

import com.viwa.android.data.remote.telemetry.mvp.offline.OfflineEntitlementSessionCoordinator
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeySessionCoordinator
import com.viwa.android.di.AppIoScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debounces network-validated side effects (outbox drain, offline entitlement, technician sync)
 * so burst ConnectivityManager callbacks do not launch parallel duplicate work.
 * WS reconnect scheduling stays immediate in [SimpleTelemetryCoordinator].
 */
@Singleton
class TelemetryNetworkValidatedSideEffectsCoordinator
@Inject
constructor(
    private val outboxDrainCoordinator: MachineOutboxDrainCoordinator,
    private val offlineEntitlementCoordinator: OfflineEntitlementSessionCoordinator,
    private val technicianKeySessionCoordinator: TechnicianKeySessionCoordinator,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private var debounceJob: Job? = null

    fun scheduleDebounced() {
        debounceJob?.cancel()
        debounceJob =
            appScope.launch {
                delay(DEBOUNCE_MS)
                runSideEffects()
            }
    }

    internal suspend fun runSideEffectsForTests() {
        runSideEffects()
    }

    internal fun debounceJobActiveForTests(): Boolean = debounceJob?.isActive == true

    private suspend fun runSideEffects() {
        runCatching { outboxDrainCoordinator.onNetworkValidated() }
            .onFailure { Timber.w(it, "TelemetryNetworkValidatedSideEffects: outbox drain failed") }
        offlineEntitlementCoordinator.onNetworkValidated()
        technicianKeySessionCoordinator.onNetworkValidated()
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
