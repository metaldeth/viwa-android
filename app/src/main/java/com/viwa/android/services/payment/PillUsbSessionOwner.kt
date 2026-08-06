package com.viwa.android.services.payment

import kotlinx.coroutines.flow.StateFlow

/**
 * Mutual exclusion for the single USB connection to the AQSI Pill.
 *
 * Customer payment ([PillUsbOwner.CUSTOMER_PAYMENT]) and future on-device Pill provisioning
 * ([PillUsbOwner.PROVISIONING]) must not open the device concurrently. Startup PAYMENT
 * assignment / NCM setup does not use this lease.
 */
interface PillUsbSessionOwner {
    suspend fun acquire(owner: PillUsbOwner, reason: String): PillUsbLeaseResult

    /** Idempotent per [PillUsbLease.id]. */
    suspend fun release(lease: PillUsbLease)

    val activeOwner: StateFlow<PillUsbOwner?>
}
