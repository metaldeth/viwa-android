package com.viwa.android.services.payment

import com.viwa.android.data.payment.aqsi.UsbPaymentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentPaymentGuardTest {
    @Test
    fun secondRunPaymentReturnsPaymentInProgress() =
        runTest {
            val guard = ConcurrentPaymentGuard()
            val first = async {
                guard.runPayment {
                    delay(500)
                    UsbPaymentResult.Success("tx-1", 100)
                }
            }
            delay(50)
            val second: UsbPaymentResult =
                guard.runPayment {
                    UsbPaymentResult.Success("tx-2", 100)
                }
            assertTrue(second is UsbPaymentResult.Failure)
            assertEquals(
                ConcurrentPaymentGuard.PAYMENT_IN_PROGRESS_CODE,
                (second as UsbPaymentResult.Failure).errorCode,
            )
            first.await()
        }

    @Test
    fun cancellationWhileWaitingForMutexReleasesInProgress() =
        runTest {
            val guard = ConcurrentPaymentGuard()
            val innerStarted = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val holder =
                async {
                    guard.runPayment {
                        innerStarted.complete(Unit)
                        hold.await()
                        UsbPaymentResult.Success("tx-holder", 100)
                    }
                }
            innerStarted.await()
            val waiter =
                async {
                    guard.runPayment {
                        UsbPaymentResult.Success("tx-waiter", 100)
                    }
                }
            delay(50)
            waiter.cancel()
            runCatching { waiter.await() }
            delay(20)
            assertTrue(guard.isPaymentInProgress)
            hold.complete(Unit)
            holder.await()
            assertFalse(guard.isPaymentInProgress)
            val retry: UsbPaymentResult =
                guard.runPayment {
                    UsbPaymentResult.Success("tx-retry", 100)
                }
            assertTrue(retry is UsbPaymentResult.Success)
        }
}
