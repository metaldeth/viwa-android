package com.viwa.android.services.payment

/** Unique token returned when [PillUsbSessionOwner.acquire] grants the USB session. */
data class PillUsbLease(
    val id: Long,
    val owner: PillUsbOwner,
    val reason: String,
)

/** Outcome of a USB session lease request. */
sealed interface PillUsbLeaseResult {
    data class Granted(val lease: PillUsbLease) : PillUsbLeaseResult

    data class Denied(
        val code: String,
        val message: String,
        val holder: PillUsbOwner,
    ) : PillUsbLeaseResult

    companion object {
        const val USB_SESSION_BUSY_CODE: String = "USB_SESSION_BUSY"
    }
}
