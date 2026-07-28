package com.viwa.android.domain.offline

import com.viwa.android.data.local.entitlement.EntitlementCacheStore
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerEntity
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerState
import com.viwa.android.data.local.entitlement.OfflineUsageLedgerStore
import com.viwa.android.data.remote.telemetry.mvp.TelemetryIsoTimestamps
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Crash-safe offline pour boundary: reserve → pouring → finalize → enqueue.
 */
@Singleton
class OfflinePourTransactionCoordinator
@Inject
constructor(
    private val ledgerStore: OfflineUsageLedgerStore,
    private val cacheStore: EntitlementCacheStore,
    private val authorizationService: OfflinePourAuthorizationService,
    private val clock: BoundedTelemetryClock,
    private val metrics: OfflineEntitlementMetrics,
) {
    suspend fun recoverUncertainStatesOnStartup() {
        val uncertain = ledgerStore.listUncertainStates()
        if (uncertain.isEmpty()) return
        val now = clock.trustedNowMs()
        uncertain.forEach { row ->
            when (OfflineUsageLedgerState.fromWire(row.state)) {
                OfflineUsageLedgerState.RESERVED -> {
                    val updated =
                        row.copy(
                            state = OfflineUsageLedgerState.REJECTED.name,
                            reconcileCode = "PROCESS_DEATH_BEFORE_POUR",
                            reconcileMessage = "Reserved before hardware; conservative reject on restart",
                            updatedAtMs = now,
                        )
                    ledgerStore.update(updated)
                    Timber.tag(TAG).w("recover RESERVED requestUuid=%s → REJECTED", row.requestUuid)
                }
                OfflineUsageLedgerState.POURING -> {
                    val updated =
                        row.copy(
                            state = OfflineUsageLedgerState.CONFLICT.name,
                            finalizedVolumeMl = 0,
                            reconcileCode = "PROCESS_DEATH_DURING_POUR",
                            reconcileMessage = "Uncertain physical pour — no duplicate hardware start",
                            updatedAtMs = now,
                        )
                    ledgerStore.update(updated)
                    metrics.recordSyncConflict()
                    Timber.tag(TAG).e("recover POURING requestUuid=%s → CONFLICT (no re-pour)", row.requestUuid)
                }
                else -> Unit
            }
        }
    }

    suspend fun reservePour(
        clientId: String,
        machineId: String,
        volumeMl: Int,
        drinkId: Int?,
        saleId: String,
        requestUuid: String,
    ): ReservePourResult {
        val existing = ledgerStore.findByRequestUuid(requestUuid)
        if (existing != null) {
            return when (OfflineUsageLedgerState.fromWire(existing.state)) {
                OfflineUsageLedgerState.RESERVED,
                OfflineUsageLedgerState.POURING,
                OfflineUsageLedgerState.FINALIZED,
                OfflineUsageLedgerState.ENQUEUED,
                OfflineUsageLedgerState.ACKED,
                -> ReservePourResult.AlreadyReserved(existing)
                else -> ReservePourResult.Denied(OfflineAuthorizationReason.OFFLINE_POUR_LIMIT)
            }
        }
        val auth = authorizationService.authorizePour(clientId, machineId, volumeMl)
        if (!auth.allowed || auth.grantId == null) {
            return ReservePourResult.Denied(auth.reason)
        }
        val subjectHash = SubjectHashUtil.computeSubjectHash(clientId)
        val now = clock.trustedNowMs()
        val row =
            OfflineUsageLedgerEntity(
                requestUuid = requestUuid,
                grantId = auth.grantId,
                subjectHash = subjectHash,
                machineId = machineId,
                saleId = saleId,
                drinkId = drinkId,
                requestedVolumeMl = volumeMl,
                finalizedVolumeMl = null,
                state = OfflineUsageLedgerState.RESERVED.name,
                soldAtMs = now,
                createdAtMs = now,
                updatedAtMs = now,
            )
        ledgerStore.insert(row)
        return ReservePourResult.Reserved(row)
    }

    suspend fun markPouring(requestUuid: String): Boolean {
        val row = ledgerStore.findByRequestUuid(requestUuid) ?: return false
        if (row.state != OfflineUsageLedgerState.RESERVED.name) return false
        ledgerStore.update(row.copy(state = OfflineUsageLedgerState.POURING.name))
        return true
    }

    suspend fun finalizePour(requestUuid: String, actualVolumeMl: Int): Boolean {
        val row = ledgerStore.findByRequestUuid(requestUuid) ?: return false
        if (row.state != OfflineUsageLedgerState.POURING.name &&
            row.state != OfflineUsageLedgerState.RESERVED.name
        ) {
            return false
        }
        ledgerStore.update(
            row.copy(
                state = OfflineUsageLedgerState.FINALIZED.name,
                finalizedVolumeMl = actualVolumeMl.coerceAtLeast(0),
            ),
        )
        return true
    }

    suspend fun enqueueForSync(requestUuid: String, clientId: String, isFree: Boolean): Boolean {
        val row = ledgerStore.findByRequestUuid(requestUuid) ?: return false
        if (row.state != OfflineUsageLedgerState.FINALIZED.name) return false
        ledgerStore.update(row.copy(state = OfflineUsageLedgerState.ENQUEUED.name))
        return true
    }

    suspend fun applyReconcileResult(
        requestUuid: String,
        status: String,
        code: String?,
        message: String?,
        subjectHash: String,
        machineId: String,
    ) {
        val row = ledgerStore.findByRequestUuid(requestUuid) ?: return
        val now = clock.trustedNowMs()
        when (status.uppercase()) {
            "ACCEPTED", "IDEMPOTENT" -> {
                ledgerStore.update(
                    row.copy(
                        state = OfflineUsageLedgerState.ACKED.name,
                        reconcileCode = code,
                        reconcileMessage = message,
                        updatedAtMs = now,
                    ),
                )
            }
            "REJECTED" -> {
                ledgerStore.update(
                    row.copy(
                        state = OfflineUsageLedgerState.REJECTED.name,
                        reconcileCode = code,
                        reconcileMessage = message,
                        updatedAtMs = now,
                    ),
                )
                cacheStore.invalidateSubject(subjectHash, machineId)
            }
            "CONFLICT" -> {
                ledgerStore.update(
                    row.copy(
                        state = OfflineUsageLedgerState.CONFLICT.name,
                        reconcileCode = code ?: "CONFLICT",
                        reconcileMessage = message,
                        updatedAtMs = now,
                    ),
                )
                cacheStore.invalidateSubject(subjectHash, machineId)
                metrics.recordSyncConflict()
            }
        }
    }

    sealed class ReservePourResult {
        data class Reserved(val row: OfflineUsageLedgerEntity) : ReservePourResult()

        data class AlreadyReserved(val row: OfflineUsageLedgerEntity) : ReservePourResult()

        data class Denied(val reason: OfflineAuthorizationReason) : ReservePourResult()
    }

    companion object {
        private const val TAG = "OfflinePourTxn"
    }
}
