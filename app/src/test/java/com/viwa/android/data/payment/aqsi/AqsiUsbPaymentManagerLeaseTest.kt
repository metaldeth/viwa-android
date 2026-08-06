package com.viwa.android.data.payment.aqsi

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialConfig
import com.viwa.android.data.payment.aqsi.serial.AqsiSerialLink
import com.viwa.android.data.payment.aqsi.serial.AqsiUsbSerialAccess
import com.viwa.android.data.payment.aqsi.support.FakeAqsiSerialSession
import com.viwa.android.data.payment.aqsi.support.FakeAqsiUsbSerialAccess
import com.viwa.android.data.payment.aqsi.support.FakePaymentSerialPort
import com.viwa.android.data.payment.aqsi.support.arcusPayQueue
import com.viwa.android.data.payment.aqsi.support.buildArcusFrame
import com.viwa.android.data.payment.aqsi.support.fakeHostNetworkBootstrap
import com.viwa.android.data.payment.aqsi.support.fakePillNetworkRouter
import com.viwa.android.hardware.serial.AqsiPillUsbIdentifiers
import com.viwa.android.hardware.serial.PaymentSerialDeviceInfo
import com.viwa.android.hardware.serial.PortRole
import com.viwa.android.services.payment.ConcurrentPaymentGuard
import com.viwa.android.services.payment.PillUsbLease
import com.viwa.android.services.payment.PillUsbLeaseResult
import com.viwa.android.services.payment.PillUsbOwner
import com.viwa.android.services.payment.PillUsbSessionOwner
import com.viwa.android.services.payment.PillUsbSessionOwnerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AqsiUsbPaymentManagerLeaseTest {
    @Test
    fun acquireBeforeOpen() =
        runTest {
            var acquiredAt: Long? = null
            var openedAt: Long? = null
            val session =
                FakeAqsiSerialSession(
                    arcusPayQueue(
                        payment =
                            listOf(
                                buildArcusFrame("STORERC", "00"),
                                buildArcusFrame("ENDTR", ""),
                            ),
                    ),
                )
            val sessionOwner =
                TrackingPillUsbSessionOwner(
                    onAcquire = { acquiredAt = System.nanoTime() },
                )
            val usbAccess =
                FakeAqsiUsbSerialAccess(listOf(FakeAqsiUsbSerialAccess.aqsiDriver())) {
                    openedAt = System.nanoTime()
                    session
                }
            val manager = manager(sessionOwner = sessionOwner, usbSerialAccess = usbAccess)

            manager.pay(100)

            assertNotNull(acquiredAt)
            assertNotNull(openedAt)
            assertTrue(acquiredAt!! <= openedAt!!)
            assertEquals(1, sessionOwner.acquireCalls)
        }

    @Test
    fun blockedWhenProvisioningHolds() =
        runTest {
            val sessionOwner = PillUsbSessionOwnerImpl()
            val provisioningLease =
                (sessionOwner.acquire(PillUsbOwner.PROVISIONING, "pill setup") as PillUsbLeaseResult.Granted).lease
            var openCalls = 0
            val usbAccess =
                FakeAqsiUsbSerialAccess(listOf(FakeAqsiUsbSerialAccess.aqsiDriver())) {
                    openCalls++
                    FakeAqsiSerialSession()
                }
            val manager = manager(sessionOwner = sessionOwner, usbSerialAccess = usbAccess)

            val result = manager.pay(100)

            assertTrue(result is UsbPaymentResult.Failure)
            assertEquals(
                PillUsbLeaseResult.USB_SESSION_BUSY_CODE,
                (result as UsbPaymentResult.Failure).errorCode,
            )
            assertEquals(0, openCalls)

            sessionOwner.release(provisioningLease)
        }

    @Test
    fun releaseAfterPaymentSession() =
        runTest {
            val sessionOwner = PillUsbSessionOwnerImpl()
            val session =
                FakeAqsiSerialSession(
                    arcusPayQueue(
                        payment =
                            listOf(
                                buildArcusFrame("STORERC", "00"),
                                buildArcusFrame("ENDTR", ""),
                            ),
                    ),
                )
            val manager = manager(sessionOwner = sessionOwner, session = session)

            manager.pay(100)

            assertNull(sessionOwner.activeOwner.value)
        }

    @Test
    fun releaseAfterOpenFailure() =
        runTest {
            val sessionOwner = PillUsbSessionOwnerImpl()
            val usbAccess = NullOpenUsbSerialAccess(listOf(FakeAqsiUsbSerialAccess.aqsiDriver()))
            val manager = manager(sessionOwner = sessionOwner, usbSerialAccess = usbAccess)

            val result = manager.pay(100)

            assertTrue(result is UsbPaymentResult.Failure)
            assertEquals("AQSI_OPEN_FAILED", (result as UsbPaymentResult.Failure).errorCode)
            assertNull(sessionOwner.activeOwner.value)
        }

    private class NullOpenUsbSerialAccess(
        private val drivers: List<UsbSerialDriver>,
    ) : AqsiUsbSerialAccess {
        override fun getAvailableDevices(): List<UsbSerialDriver> = drivers

        override fun openConnection(
            driver: UsbSerialDriver,
            portIndex: Int,
            config: AqsiSerialConfig,
        ): Pair<AqsiSerialLink, UsbDeviceConnection>? = null
    }

    private class TrackingPillUsbSessionOwner(
        private val onAcquire: () -> Unit = {},
    ) : PillUsbSessionOwner {
        var acquireCalls: Int = 0
        private var nextLeaseId = 0L
        private val _activeOwner = MutableStateFlow<PillUsbOwner?>(null)

        override val activeOwner: StateFlow<PillUsbOwner?> = _activeOwner

        override suspend fun acquire(owner: PillUsbOwner, reason: String): PillUsbLeaseResult {
            acquireCalls++
            onAcquire()
            nextLeaseId++
            _activeOwner.value = owner
            return PillUsbLeaseResult.Granted(
                PillUsbLease(id = nextLeaseId, owner = owner, reason = reason),
            )
        }

        override suspend fun release(lease: PillUsbLease) {
            _activeOwner.value = null
        }
    }

    private fun manager(
        session: FakeAqsiSerialSession = FakeAqsiSerialSession(),
        sessionOwner: PillUsbSessionOwner = PillUsbSessionOwnerImpl(),
        usbSerialAccess: AqsiUsbSerialAccess =
            FakeAqsiUsbSerialAccess(listOf(FakeAqsiUsbSerialAccess.aqsiDriver())) { session },
    ): AqsiUsbPaymentManager {
        val serialPort =
            FakePaymentSerialPort(
                devices =
                    listOf(
                        PaymentSerialDeviceInfo(
                            deviceName = "aqsi-test",
                            vendorId = AqsiPillUsbIdentifiers.VENDOR_ID,
                            productId = AqsiPillUsbIdentifiers.PRODUCT_ID,
                            driverType = "CdcAcmSerialDriver",
                        ),
                    ),
            ).apply {
                assignments["aqsi-test"] = PortRole.PAYMENT
            }
        return AqsiUsbPaymentManager(
            usbSerialAccess = usbSerialAccess,
            serialPort = serialPort,
            audit = AqsiPaymentAuditLogger(),
            pillNetworkRouter = fakePillNetworkRouter(),
            hostNetworkBootstrap = fakeHostNetworkBootstrap(),
            pillUsbSessionOwner = sessionOwner,
            concurrentPaymentGuard = ConcurrentPaymentGuard(),
        )
    }
}
