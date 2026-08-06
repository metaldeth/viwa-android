package com.viwa.android.data.payment.aqsi.support

import com.viwa.android.data.payment.aqsi.network.AqsiPillHostNetworkBootstrap
import com.viwa.android.data.payment.aqsi.network.AqsiPillNetworkRouter
import com.viwa.android.hardware.serial.PaymentSerialDeviceInfo
import com.viwa.android.hardware.serial.PaymentSerialPort
import com.viwa.android.hardware.serial.PortRole
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.net.Socket

class FakePaymentSerialPort(
    private val devices: List<PaymentSerialDeviceInfo> = emptyList(),
) : PaymentSerialPort {
    val assignments = mutableMapOf<String, PortRole>()

    override suspend fun availableDevices(): List<PaymentSerialDeviceInfo> = devices

    override suspend fun assignments(): Map<String, PortRole> = assignments.toMap()

    override suspend fun assign(deviceName: String, role: PortRole): Result<Unit> {
        assignments.entries.removeAll { it.value == role }
        assignments[deviceName] = role
        return Result.success(Unit)
    }

    override suspend fun assignedDeviceName(role: PortRole): String? =
        assignments.entries.firstOrNull { it.value == role }?.key?.let { key ->
            if (devices.any { it.deviceName == key }) key else null
        }
}

fun fakeHostNetworkBootstrap(): AqsiPillHostNetworkBootstrap =
    mockk(relaxed = true) {
        val readyStatus =
            AqsiPillHostNetworkBootstrap.HostNetworkStatus(
                httpProxyCleared = true,
                ncmReady = true,
                wifiProcessBound = true,
                wifiInternetProbe = true,
                socksStarted = true,
                paymentFastPath = false,
            )
        val paymentStatus = readyStatus.copy(paymentFastPath = true)
        coEvery { runWhenPillPresent() } returns readyStatus
        coEvery { runForPayment() } returns paymentStatus
    }

fun fakePillNetworkRouter(): AqsiPillNetworkRouter =
    mockk(relaxed = true) {
        every { connectToPill(any(), any(), any(), any()) } answers {
            val socket = firstArg<Socket>()
            socket.connect(InetSocketAddress(secondArg<String>(), thirdArg()), lastArg())
        }
        every { connectForInternet(any(), any(), any(), any()) } answers {
            val socket = firstArg<Socket>()
            socket.connect(InetSocketAddress(secondArg<String>(), thirdArg()), lastArg())
        }
    }
