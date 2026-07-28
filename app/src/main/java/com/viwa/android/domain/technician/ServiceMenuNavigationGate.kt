package com.viwa.android.domain.technician

import com.viwa.android.domain.offline.BoundedTelemetryClock
import javax.inject.Inject
import javax.inject.Singleton

/** Central gate for every navigation path into [com.viwa.android.ui.screens.service.ServiceScreen]. */
@Singleton
class ServiceMenuNavigationGate
@Inject
constructor(
    private val access: TechnicianServiceMenuAccess,
    private val sessionStore: TechnicianSessionStore,
    private val clock: BoundedTelemetryClock,
) {
    fun isAuthorized(): Boolean = access.isAuthorized(clock.trustedNowMs())

    fun navigateIfAuthorized(navigate: () -> Unit) {
        if (isAuthorized()) navigate()
    }

    /**
     * Legacy password `"studio"` path: establishes a local offline-scoped session, then navigates.
     * KEY-* scan path must keep using [navigateIfAuthorized] after real authorization.
     */
    fun navigateAfterLocalStudioPassword(navigate: () -> Unit) {
        val nowMs = clock.trustedNowMs()
        sessionStore.establish(
            technicianKeyId = LOCAL_STUDIO_TECHNICIAN_KEY_ID,
            scopes = TechnicianKeyConstants.OFFLINE_SCOPES.toList(),
            sessionToken = "local-studio",
            expiresAtMs = nowMs + TechnicianKeyConstants.SESSION_TTL_MS,
        )
        navigate()
    }

    companion object {
        const val LOCAL_STUDIO_TECHNICIAN_KEY_ID = "local:studio"
    }
}
