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
    private val clock: BoundedTelemetryClock,
) {
    fun isAuthorized(): Boolean = access.isAuthorized(clock.trustedNowMs())

    fun navigateIfAuthorized(navigate: () -> Unit) {
        if (isAuthorized()) navigate()
    }
}
