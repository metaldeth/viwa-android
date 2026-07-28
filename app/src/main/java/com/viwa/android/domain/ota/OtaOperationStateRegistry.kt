package com.viwa.android.domain.ota

import javax.inject.Inject
import javax.inject.Singleton

/** Process-wide flags for service-critical operations — avoids DI cycle with PreparingManager. */
@Singleton
class OtaOperationStateRegistry
@Inject
constructor() {
    @Volatile
    var pourInProgress: Boolean = false

    @Volatile
    var paymentInProgress: Boolean = false

    fun isCriticalOperationActive(): Boolean = pourInProgress || paymentInProgress
}
