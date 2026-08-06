package com.viwa.android.services.payment

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process mutex for the single AQSI Pill USB session.
 *
 * Separate from [ConcurrentPaymentGuard]: the USB lease is the first gate before
 * opening the device; the guard still blocks overlapping customer payment attempts.
 */
@Singleton
class PillUsbSessionOwnerImpl
@Inject
constructor() : PillUsbSessionOwner {
    private val mutex = Mutex()
    private val nextLeaseId = AtomicLong(1L)
    private val activeOwnerState = MutableStateFlow<PillUsbOwner?>(null)

    private var holder: PillUsbOwner? = null
    private val outstandingLeaseIds = mutableSetOf<Long>()

    override val activeOwner: StateFlow<PillUsbOwner?> = activeOwnerState.asStateFlow()

    override suspend fun acquire(owner: PillUsbOwner, reason: String): PillUsbLeaseResult =
        mutex.withLock {
            val current = holder
            when {
                current == null -> grant(owner, reason)
                current == owner -> grant(owner, reason)
                else ->
                    PillUsbLeaseResult.Denied(
                        code = PillUsbLeaseResult.USB_SESSION_BUSY_CODE,
                        message = "USB session held by $current",
                        holder = current,
                    )
            }
        }

    override suspend fun release(lease: PillUsbLease) =
        mutex.withLock {
            if (holder != lease.owner) {
                return@withLock
            }
            if (!outstandingLeaseIds.remove(lease.id)) {
                return@withLock
            }
            if (outstandingLeaseIds.isEmpty()) {
                holder = null
                activeOwnerState.value = null
            }
        }

    private fun grant(owner: PillUsbOwner, reason: String): PillUsbLeaseResult.Granted {
        if (holder == null) {
            holder = owner
            activeOwnerState.value = owner
        }
        val lease =
            PillUsbLease(
                id = nextLeaseId.getAndIncrement(),
                owner = owner,
                reason = reason,
            )
        outstandingLeaseIds.add(lease.id)
        return PillUsbLeaseResult.Granted(lease)
    }
}
