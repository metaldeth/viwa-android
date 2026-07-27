package com.viwa.android.hardware.devices



import com.viwa.android.data.local.db.JsonStoreKeys

import com.viwa.android.data.repository.ConfigRepository

import com.viwa.android.hardware.controller.ControllerHardwareManager

import com.viwa.android.hardware.serial.UsbSerialManager

import javax.inject.Inject

import javax.inject.Singleton

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext



@Singleton

class ViwaControllerPortProbe

@Inject

constructor(

    private val controllerHardware: ControllerHardwareManager,

    private val configRepository: ConfigRepository,

    private val usbSerialManager: UsbSerialManager,

) {

    suspend fun discover(): String? =

        withContext(Dispatchers.IO) {

            if (configRepository.get(JsonStoreKeys.USE_MOCK_CONTROLLER)?.toBooleanStrictOrNull() == true) {

                return@withContext null

            }

            val nativeCandidates =

                com.viwa.android.hardware.NativeSerialPortDetector.detectPortPaths()

            val usbCandidates =

                usbSerialManager.getAvailableDevices().map { it.device.deviceName }

            val candidates = nativeCandidates + usbCandidates

            for (path in candidates) {

                val result = runCatching { controllerHardware.connectToPort(path) }.getOrNull()

                if (result?.success == true) {

                    return@withContext path

                }

            }

            null

        }

}

