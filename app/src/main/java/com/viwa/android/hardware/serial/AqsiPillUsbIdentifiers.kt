package com.viwa.android.hardware.serial

import android.hardware.usb.UsbDevice

/** USB identifiers for AQSI Pill T7100 (CDC serial + NCM). */
object AqsiPillUsbIdentifiers {
    const val VENDOR_ID = 0x0FB9
    const val PRODUCT_ID = 0x2606

    fun isAqsiPill(device: PaymentSerialDeviceInfo): Boolean =
        device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID

    fun isAqsiPill(device: SerialDeviceInfo): Boolean =
        device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID

    fun isAqsiPill(device: UsbDevice): Boolean =
        device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
}
