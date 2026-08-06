package com.viwa.android.services.payment

import app.cash.turbine.test
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillUsbSessionOwnerImplTest {
    private val owner = PillUsbSessionOwnerImpl()

    @Test
    fun provisioningBlockedWhilePaymentHolds() =
        runTest {
            val paymentLease =
                (owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "card pay") as PillUsbLeaseResult.Granted).lease

            val result = owner.acquire(PillUsbOwner.PROVISIONING, "pill setup")

            assertTrue(result is PillUsbLeaseResult.Denied)
            val denied = result as PillUsbLeaseResult.Denied
            assertEquals(PillUsbLeaseResult.USB_SESSION_BUSY_CODE, denied.code)
            assertEquals(PillUsbOwner.CUSTOMER_PAYMENT, denied.holder)
            assertEquals(PillUsbOwner.CUSTOMER_PAYMENT, owner.activeOwner.value)

            owner.release(paymentLease)
        }

    @Test
    fun paymentBlockedWhileProvisioningHolds() =
        runTest {
            val provisioningLease =
                (owner.acquire(PillUsbOwner.PROVISIONING, "pill setup") as PillUsbLeaseResult.Granted).lease

            val result = owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "card pay")

            assertTrue(result is PillUsbLeaseResult.Denied)
            val denied = result as PillUsbLeaseResult.Denied
            assertEquals(PillUsbLeaseResult.USB_SESSION_BUSY_CODE, denied.code)
            assertEquals(PillUsbOwner.PROVISIONING, denied.holder)

            owner.release(provisioningLease)
        }

    @Test
    fun releaseAllowsOtherOwner() =
        runTest {
            val provisioningLease =
                (owner.acquire(PillUsbOwner.PROVISIONING, "pill setup") as PillUsbLeaseResult.Granted).lease
            owner.release(provisioningLease)
            assertNull(owner.activeOwner.value)

            val result = owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "card pay")

            assertTrue(result is PillUsbLeaseResult.Granted)
            assertEquals(PillUsbOwner.CUSTOMER_PAYMENT, owner.activeOwner.value)
            owner.release((result as PillUsbLeaseResult.Granted).lease)
        }

    @Test
    fun activeOwnerStateFlow() =
        runTest {
            owner.activeOwner.test {
                assertEquals(null, awaitItem())

                val granted =
                    owner.acquire(PillUsbOwner.PROVISIONING, "pill setup")
                        as PillUsbLeaseResult.Granted
                assertEquals(PillUsbOwner.PROVISIONING, awaitItem())

                owner.release(granted.lease)
                assertEquals(null, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun sameOwnerReentrantAcquireRelease() =
        runTest {
            val outer =
                owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "outer pay")
                    as PillUsbLeaseResult.Granted
            val inner =
                owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "inner pay")
                    as PillUsbLeaseResult.Granted
            assertNotEquals(outer.lease.id, inner.lease.id)

            owner.release(inner.lease)
            assertEquals(PillUsbOwner.CUSTOMER_PAYMENT, owner.activeOwner.value)

            owner.release(outer.lease)
            assertNull(owner.activeOwner.value)
        }

    @Test
    fun doubleReleaseSameLeaseIsIdempotent() =
        runTest {
            val first =
                owner.acquire(PillUsbOwner.PROVISIONING, "step 1")
                    as PillUsbLeaseResult.Granted
            val second =
                owner.acquire(PillUsbOwner.PROVISIONING, "step 2")
                    as PillUsbLeaseResult.Granted

            owner.release(first.lease)
            assertEquals(PillUsbOwner.PROVISIONING, owner.activeOwner.value)

            owner.release(first.lease)
            assertEquals(PillUsbOwner.PROVISIONING, owner.activeOwner.value)

            owner.release(second.lease)
            assertNull(owner.activeOwner.value)
        }

    @Test
    fun parallelCrossOwnerAcquireRace() =
        runTest {
            val payment = async { owner.acquire(PillUsbOwner.CUSTOMER_PAYMENT, "race pay") }
            val provisioning = async { owner.acquire(PillUsbOwner.PROVISIONING, "race setup") }

            val results = listOf(payment.await(), provisioning.await())
            val granted = results.filterIsInstance<PillUsbLeaseResult.Granted>()
            val denied = results.filterIsInstance<PillUsbLeaseResult.Denied>()

            assertEquals(1, granted.size)
            assertEquals(1, denied.size)
            assertEquals(PillUsbLeaseResult.USB_SESSION_BUSY_CODE, denied.single().code)

            owner.release(granted.single().lease)
            assertNull(owner.activeOwner.value)
        }
}
