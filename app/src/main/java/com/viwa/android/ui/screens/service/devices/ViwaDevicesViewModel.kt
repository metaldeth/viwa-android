package com.viwa.android.ui.screens.service.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viwa.android.data.payment.aqsi.AqsiUsbPaymentManager
import com.viwa.android.data.payment.aqsi.setup.AqsiPaymentStartupInitializer
import com.viwa.android.hardware.controller.ControllerHardwareManager
import com.viwa.android.hardware.devices.ViwaControllerMockModePort
import com.viwa.android.hardware.devices.ViwaControllerPortProbe
import com.viwa.android.hardware.devices.ViwaControllerPortScanPort
import com.viwa.android.hardware.devices.ViwaControllerManualPortProbe
import com.viwa.android.hardware.devices.ViwaDeviceRuntimeDiscovery
import com.viwa.android.hardware.scanner.ScannerManager
import com.viwa.android.hardware.scanner.ViwaScannerPortFacade
import com.viwa.android.hardware.scanner.ViwaScannerStartupInitializer
import com.viwa.android.hardware.scanner.ViwaScannerTrafficLogger
import com.viwa.android.hardware.serial.SerialPortAssignmentEvents
import com.viwa.android.hardware.serial.ViwaSerialPort
import com.viwa.android.services.payment.PillUsbSessionOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ViwaDevicesViewModel
@Inject
constructor(
    serialPort: ViwaSerialPort,
    scannerPortFacade: ViwaScannerPortFacade,
    controllerPortProbe: ViwaControllerPortProbe,
    controllerMockModePort: ViwaControllerMockModePort,
    assignmentEvents: SerialPortAssignmentEvents,
    deviceRuntimeDiscovery: ViwaDeviceRuntimeDiscovery,
    aqsiPaymentStartupInitializer: AqsiPaymentStartupInitializer,
    scannerStartupInitializer: ViwaScannerStartupInitializer,
    aqsiUsbPaymentManager: AqsiUsbPaymentManager,
    pillUsbSessionOwner: PillUsbSessionOwner,
    scannerTrafficLogger: ViwaScannerTrafficLogger,
    portScanPort: ViwaControllerPortScanPort,
    manualPortProbe: ViwaControllerManualPortProbe,
    controllerHardware: ControllerHardwareManager,
    private val scannerManager: ScannerManager,
) : ViewModel() {

    val controllerPortController: ControllerPortBlockController =
        ControllerPortBlockController(
            serialPort = serialPort,
            controllerHardware = controllerHardware,
            assignmentEvents = assignmentEvents,
            scope = viewModelScope,
        )

    val devicesController: DevicesBlockController =
        DevicesBlockController(
            serialPort = serialPort,
            scannerPort = scannerPortFacade,
            controllerPortProbe = controllerPortProbe,
            controllerMockModePort = controllerMockModePort,
            controllerHardware = controllerHardware,
            assignmentEvents = assignmentEvents,
            deviceRuntimeDiscovery = deviceRuntimeDiscovery,
            aqsiPaymentStartupInitializer = aqsiPaymentStartupInitializer,
            scannerStartupInitializer = scannerStartupInitializer,
            pillUsbSessionOwner = pillUsbSessionOwner,
            scope = viewModelScope,
        )

    val paymentTerminalController: PaymentTerminalBlockController =
        PaymentTerminalBlockController(
            aqsiUsbPaymentManager = aqsiUsbPaymentManager,
            serialPort = serialPort,
            pillUsbSessionOwner = pillUsbSessionOwner,
            parentScope = viewModelScope,
        )

    val scannerDebugController: ScannerDebugBlockController =
        ScannerDebugBlockController(
            scannerTrafficLogger = scannerTrafficLogger,
            scope = viewModelScope,
        )

    val portScanController: ViwaControllerPortScanController =
        ViwaControllerPortScanController(
            scanner = portScanPort,
            manualProbe = manualPortProbe,
            serialPort = serialPort,
            labels = ViwaDevicesUiLabels(),
            scope = viewModelScope,
        )

    val scannerActive: StateFlow<Boolean> = scannerManager.scannerSerialActive

    fun startScanner() {
        scannerManager.startReading()
    }

    fun stopScanner() {
        scannerManager.stop()
    }

    override fun onCleared() {
        paymentTerminalController.close()
        portScanController.cancelAllProbes()
        super.onCleared()
    }
}
