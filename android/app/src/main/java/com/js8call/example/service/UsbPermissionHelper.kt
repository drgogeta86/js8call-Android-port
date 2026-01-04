package com.js8call.example.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Helper class for managing USB device permissions.
 */
object UsbPermissionHelper {
    private const val TAG = "UsbPermissionHelper"
    private const val ACTION_USB_PERMISSION = "com.js8call.example.USB_PERMISSION"

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission to access the USB device.
     * The callback will be invoked with true if permission is granted, false otherwise.
     */
    fun requestPermission(context: Context, device: UsbDevice, callback: (Boolean) -> Unit) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        // Check if we already have permission
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already have USB permission for device ${device.deviceName}")
            callback(true)
            return
        }

        // Create a broadcast receiver to listen for the permission result
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (device != null) {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted) {
                                Log.i(TAG, "USB permission granted for device ${device.deviceName}")
                                callback(true)
                            } else {
                                Log.w(TAG, "USB permission denied for device ${device.deviceName}")
                                callback(false)
                            }
                        }

                        // Unregister the receiver
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error unregistering receiver: ${e.message}")
                        }
                    }
                }
            }
        }

        // Register the receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }

        // Create pending intent for the permission dialog
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // Request permission
        Log.d(TAG, "Requesting USB permission for device ${device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Find the first available USB serial device.
     * Returns null if no device is found.
     */
    fun findFirstUsbDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            Log.d(TAG, "No USB devices found")
            return null
        }

        Log.d(TAG, "Found ${deviceList.size} USB device(s)")
        deviceList.values.forEach { device ->
            Log.d(TAG, "  - ${device.deviceName}: VID=${device.vendorId} PID=${device.productId}")
        }

        // Return the first device
        return deviceList.values.firstOrNull()
    }

    /**
     * Find a USB device by Android device ID.
     * Returns null if not found.
     */
    fun findUsbDeviceById(context: Context, deviceId: Int): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            Log.d(TAG, "No USB device found with deviceId=$deviceId")
        }
        return device
    }
}
