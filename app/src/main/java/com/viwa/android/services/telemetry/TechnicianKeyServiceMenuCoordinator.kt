package com.viwa.android.services.telemetry

import com.viwa.android.data.local.technician.TechnicianKeyPolicyStore
import com.viwa.android.data.remote.telemetry.ConnectionState
import com.viwa.android.data.remote.telemetry.mvp.MvpTelemetryWebSocketManager
import com.viwa.android.data.remote.telemetry.mvp.offline.MvpTechnicianKeysCapabilityDto
import com.viwa.android.data.remote.telemetry.mvp.offline.TechnicianKeyFeatureFlags
import com.viwa.android.data.repository.ConfigRepository
import com.viwa.android.di.AppIoScope
import com.viwa.android.domain.model.BarcodeEvent
import com.viwa.android.domain.technician.TechnicianAuthorizationReason
import com.viwa.android.domain.technician.TechnicianKeyAuthorizationService
import com.viwa.android.domain.technician.TechnicianKeyConstants
import com.viwa.android.domain.technician.TechnicianKeyOnlineValidator
import com.viwa.android.domain.technician.TechnicianKeyPolicyResolver
import com.viwa.android.hardware.scanner.ScannerManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

private const val TAG = "TechKeyMenu"

/**
 * Scanner `KEY-*` / `EMP:` on customer screen: online validate or signed offline allowlist;
 * opens service menu on success.
 */
@Singleton
class TechnicianKeyServiceMenuCoordinator
@Inject
constructor(
    private val scannerManager: ScannerManager,
    private val wsManager: MvpTelemetryWebSocketManager,
    private val onlineValidator: TechnicianKeyOnlineValidator,
    private val offlineAuthorization: TechnicianKeyAuthorizationService,
    private val policyStore: TechnicianKeyPolicyStore,
    private val configRepository: ConfigRepository,
    @AppIoScope private val appScope: CoroutineScope,
) {
    private val _openServiceMenu =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val openServiceMenuRequests: SharedFlow<Unit> = _openServiceMenu.asSharedFlow()

    init {
        appScope.launch {
            scannerManager.barcodeFlow.collect { event ->
                if (event !is BarcodeEvent.EmployeeKey) return@collect
                handleEmployeeKeyScan(event.code)
            }
        }
    }

    private suspend fun handleEmployeeKeyScan(rawCode: String) {
        val policy = policyStore.read()
        val liveServerFlag = wsManager.serverTechnicianKeysEnabled()
        if (!TechnicianKeyPolicyResolver.isRuntimeEnabled(policy.serverTechnicianKeysEnabled, liveServerFlag)) {
            Timber.tag(TAG).w("scan ignored: technician keys disabled (persisted=%s live=%s)", policy.serverTechnicianKeysEnabled, liveServerFlag)
            return
        }
        val capability = resolveCapability(policy.capability)
        if (capability == null) {
            Timber.tag(TAG).w("scan ignored: no persisted technicianKeys capability")
            return
        }
        val machineId = loadMachineId()
        if (machineId.isBlank()) {
            Timber.tag(TAG).w("scan ignored: machine not registered")
            return
        }
        val requestUuid = UUID.randomUUID().toString()
        val requestedScope = TechnicianKeyConstants.SCOPE_SERVICE_MENU
        val connected = wsManager.connectionState.value is ConnectionState.Connected
        val result =
            if (connected && wsManager.isNetworkValidated()) {
                val online = onlineValidator.validateOnline(capability, rawCode, requestedScope, requestUuid)
                if (online.allowed) {
                    online
                } else if (online.transportFailure) {
                    offlineAuthorization.authorizeOffline(rawCode, machineId, requestedScope, requestUuid)
                } else {
                    online
                }
            } else {
                offlineAuthorization.authorizeOffline(rawCode, machineId, requestedScope, requestUuid)
            }
        if (result.allowed) {
            _openServiceMenu.emit(Unit)
            Timber.tag(TAG).i("service menu opened channel=%s", result.channel)
        } else {
            Timber.tag(TAG).i(
                "access denied channel=%s reason=%s",
                result.channel,
                result.reason.name,
            )
            if (result.reason == TechnicianAuthorizationReason.OFFLINE_SCOPE_DENIED) {
                Timber.tag(TAG).w("high-risk scope blocked offline")
            }
        }
    }

    private suspend fun resolveCapability(persisted: MvpTechnicianKeysCapabilityDto?): MvpTechnicianKeysCapabilityDto? =
        TechnicianKeyPolicyResolver.mergeCapabilityFromHello(persisted, wsManager.technicianKeysCapability())

    private suspend fun loadMachineId(): String {
        val json = configRepository.get(com.viwa.android.data.local.db.JsonStoreKeys.MACHINE_REGISTRATION).orEmpty()
        if (json.isBlank()) return ""
        return runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<com.viwa.android.domain.model.MachineRegistration>(json)
                .machineId
        }.getOrDefault("")
    }
}
