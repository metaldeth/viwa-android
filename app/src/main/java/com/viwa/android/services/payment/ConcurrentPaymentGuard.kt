package com.viwa.android.services.payment

import com.viwa.android.data.payment.aqsi.UsbPaymentResult
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prevents overlapping customer payment attempts.
 *
 * Second [runPayment] while the first is active returns [UsbPaymentResult.Failure]
 * with code [PAYMENT_IN_PROGRESS_CODE].
 */
@Singleton
class ConcurrentPaymentGuard
@Inject
constructor() {
    private val mutex = Mutex()
    private val inProgress = AtomicBoolean(false)

    val isPaymentInProgress: Boolean
        get() = inProgress.get()

    suspend fun <T> runPayment(block: suspend () -> T): T {
        if (!inProgress.compareAndSet(false, true)) {
            @Suppress("UNCHECKED_CAST")
            return UsbPaymentResult.Failure(
                errorCode = PAYMENT_IN_PROGRESS_CODE,
                message = PAYMENT_IN_PROGRESS_MESSAGE,
            ) as T
        }
        try {
            return mutex.withLock {
                block()
            }
        } finally {
            inProgress.set(false)
        }
    }

    companion object {
        const val PAYMENT_IN_PROGRESS_CODE: String = "PAYMENT_IN_PROGRESS"
        const val PAYMENT_IN_PROGRESS_MESSAGE: String = "Payment already in progress"
    }
}
