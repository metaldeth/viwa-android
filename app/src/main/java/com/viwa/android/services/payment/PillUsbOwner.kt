package com.viwa.android.services.payment

/**
 * Exclusive USB session holder for the AQSI Pill terminal.
 *
 * [CUSTOMER_PAYMENT] — customer or service-menu Arcus2 payment/test on the assigned USB device.
 *
 * [PROVISIONING] — reserved for a future on-device Pill provisioning flow that opens the USB
 * serial connection (factory/setup PC parity). **Not** used by [com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer]
 * cold-start assignment or NCM/network bootstrap — those do not acquire a Pill USB lease.
 */
enum class PillUsbOwner {
    CUSTOMER_PAYMENT,
    PROVISIONING,
}
