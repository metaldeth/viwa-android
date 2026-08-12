package com.viwa.android.domain.ota

internal object OtaPersistedStateRecovery {
    fun isStale(
        installedVersionCode: Int,
        targetVersionCode: Int?,
        offerVersionCode: Int?,
        pendingApkVersionCode: Int?,
    ): Boolean {
        val effectiveTarget = offerVersionCode ?: targetVersionCode ?: pendingApkVersionCode
        if (effectiveTarget != null && installedVersionCode >= effectiveTarget) return true
        if (offerVersionCode != null && offerVersionCode < installedVersionCode) return true
        if (pendingApkVersionCode != null && pendingApkVersionCode <= installedVersionCode) return true
        return false
    }

    fun needsProcessDeathRecovery(phase: AppUpdatePhase): Boolean =
        phase == AppUpdatePhase.AwaitingUser ||
            phase == AppUpdatePhase.Installing ||
            phase == AppUpdatePhase.Downloading ||
            phase == AppUpdatePhase.Verifying

    fun planProcessDeathRecovery(
        phase: AppUpdatePhase,
        offer: OtaUpdateOffer?,
        persistedPendingPath: String?,
        safePendingApkPath: String?,
    ): OtaProcessDeathRecoveryPlan {
        if (!needsProcessDeathRecovery(phase)) {
            return OtaProcessDeathRecoveryPlan(
                targetPhase = phase,
                pendingApkPath = persistedPendingPath,
                shouldDeletePendingApk = false,
                clearAllState = false,
            )
        }
        if (offer == null) {
            return OtaProcessDeathRecoveryPlan(
                targetPhase = AppUpdatePhase.Idle,
                pendingApkPath = null,
                shouldDeletePendingApk = true,
                clearAllState = true,
            )
        }
        val interruptedDownload =
            phase == AppUpdatePhase.Downloading || phase == AppUpdatePhase.Verifying
        val shouldDeletePendingApk =
            interruptedDownload || (persistedPendingPath != null && safePendingApkPath == null)
        return OtaProcessDeathRecoveryPlan(
            targetPhase = AppUpdatePhase.Offered,
            pendingApkPath = if (interruptedDownload) null else safePendingApkPath,
            shouldDeletePendingApk = shouldDeletePendingApk,
            clearAllState = false,
        )
    }
}

internal data class OtaProcessDeathRecoveryPlan(
    val targetPhase: AppUpdatePhase,
    val pendingApkPath: String?,
    val shouldDeletePendingApk: Boolean,
    val clearAllState: Boolean,
)
