package com.js8call.core

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Lists available USB serial ports for settings UI.
 */
object UsbSerialPortCatalog {
    data class SerialPort(val deviceId: Int, val portIndex: Int, val label: String)

    fun listPorts(context: Context): List<SerialPort> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) return emptyList()

        val ports = ArrayList<SerialPort>()
        for (driver in drivers) {
            val device = driver.device
            val labelBase = buildLabel(driver)
            driver.ports.forEachIndexed { index, _ ->
                ports.add(SerialPort(device.deviceId, index, "$labelBase Port $index"))
            }
        }
        return ports
    }

    private fun buildLabel(driver: UsbSerialDriver): String {
        val device = driver.device
        val driverName = driver.javaClass.simpleName
        val vid = toHex(device.vendorId)
        val pid = toHex(device.productId)
        return "$driverName $vid:$pid (id ${device.deviceId})"
    }

    private fun toHex(value: Int): String {
        return value.toString(16).uppercase().padStart(4, '0')
    }
}
