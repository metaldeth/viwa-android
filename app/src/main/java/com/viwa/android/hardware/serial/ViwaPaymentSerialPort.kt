package com.viwa.android.hardware.serial



import javax.inject.Inject

import javax.inject.Singleton



@Singleton

class ViwaPaymentSerialPort

@Inject

constructor(

    private val serialPort: ViwaSerialPort,

) : PaymentSerialPort {



    override suspend fun availableDevices(): List<PaymentSerialDeviceInfo> =

        serialPort.availableDevices().map { device ->

            PaymentSerialDeviceInfo(

                deviceName = device.deviceName,

                vendorId = device.vendorId,

                productId = device.productId,

                driverType = device.driverType,

            )

        }



    override suspend fun assignments(): Map<String, PortRole> = serialPort.assignments()



    override suspend fun assign(deviceName: String, role: PortRole): Result<Unit> =

        serialPort.assign(deviceName, role)



    override suspend fun assignedDeviceName(role: PortRole): String? =

        serialPort.assignedDeviceName(role)

}

