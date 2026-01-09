package com.js8call.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lists paired Bluetooth serial devices for settings UI.
 */
object BluetoothSerialPortCatalog {
    private const val TAG = "BluetoothSerialPortCatalog"
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cachedServiceUuids = ConcurrentHashMap<String, List<UUID>>()

    data class SerialPort(val address: String, val portIndex: Int, val label: String)

    fun listPorts(context: Context): List<SerialPort> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        if (!hasBluetoothPermission(context)) {
            Log.w(TAG, "Bluetooth permissions not granted; Bluetooth ports unavailable")
            return emptyList()
        }
        val devices = adapter.bondedDevices ?: emptySet()
        if (devices.isEmpty()) return emptyList()

        val ports = ArrayList<SerialPort>()
        for (device in devices) {
            if (!isSppDevice(device)) {
                continue
            }
            val normalized = normalizeAddress(device.address) ?: continue
            val serviceUuids = getServiceUuids(device, normalized)
            val serialUuids = filterSerialUuids(serviceUuids)
            val portIndices = if (serialUuids.size > 1) {
                listOf(0, 1)
            } else {
                listOf(0)
            }
            val name = device.name ?: "Unknown"
            for (portIndex in portIndices) {
                val label = "Bluetooth $name (${device.address}) Port $portIndex"
                ports.add(SerialPort(normalized, portIndex, label))
            }
        }
        return ports
    }

    fun findBondedDevice(context: Context, addressNoColons: String): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!hasBluetoothPermission(context)) return null
        val target = normalizeAddress(addressNoColons) ?: return null
        return adapter.bondedDevices?.firstOrNull { device ->
            normalizeAddress(device.address) == target
        }
    }

    fun normalizeAddress(address: String): String? {
        val normalized = address.replace(":", "").uppercase()
        if (normalized.length != 12) return null
        for (ch in normalized) {
            val ok = (ch in '0'..'9') || (ch in 'A'..'F')
            if (!ok) return null
        }
        return normalized
    }

    fun formatAddress(addressNoColons: String): String? {
        val normalized = normalizeAddress(addressNoColons) ?: return null
        return normalized.chunked(2).joinToString(":")
    }

    fun cacheServiceUuids(addressNoColons: String, uuids: List<UUID>) {
        val normalized = normalizeAddress(addressNoColons) ?: return
        cachedServiceUuids[normalized] = orderServiceUuids(uuids)
    }

    fun getCachedServiceUuids(addressNoColons: String): List<UUID> {
        val normalized = normalizeAddress(addressNoColons) ?: return emptyList()
        return cachedServiceUuids[normalized].orEmpty()
    }

    fun getSerialServiceUuids(addressNoColons: String): List<UUID> {
        return filterSerialUuids(getCachedServiceUuids(addressNoColons))
    }

    fun filterSerialUuids(uuids: List<UUID>): List<UUID> {
        if (uuids.isEmpty()) return emptyList()
        val filtered = ArrayList<UUID>()
        for (uuid in uuids) {
            if (uuid == SPP_UUID) {
                filtered.add(uuid)
            } else if (!isBluetoothBaseUuid(uuid)) {
                filtered.add(uuid)
            }
        }
        return filtered.distinct()
    }

    private fun getServiceUuids(device: BluetoothDevice, normalizedAddress: String): List<UUID> {
        val cached = cachedServiceUuids[normalizedAddress]
        if (!cached.isNullOrEmpty()) {
            return cached
        }
        val deviceUuids = device.uuids?.map { it.uuid }?.distinct().orEmpty()
        if (deviceUuids.isNotEmpty()) {
            val ordered = orderServiceUuids(deviceUuids)
            cachedServiceUuids[normalizedAddress] = ordered
            return ordered
        }
        try {
            device.fetchUuidsWithSdp()
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth UUID fetch failed: ${e.message}")
        }
        return emptyList()
    }

    private fun orderServiceUuids(uuids: List<UUID>): List<UUID> {
        if (uuids.isEmpty()) return emptyList()
        val unique = LinkedHashSet(uuids)
        val ordered = ArrayList<UUID>()
        if (unique.remove(SPP_UUID)) {
            ordered.add(SPP_UUID)
        }
        ordered.addAll(unique.toList().sortedBy { it.toString() })
        return ordered
    }

    private fun isBluetoothBaseUuid(uuid: UUID): Boolean {
        val text = uuid.toString().lowercase()
        return text.startsWith("0000") && text.endsWith("-0000-1000-8000-00805f9b34fb")
    }

    private fun isSppDevice(device: BluetoothDevice): Boolean {
        val uuids = device.uuids
        if (uuids != null) {
            for (uuid in uuids) {
                if (uuid.uuid == SPP_UUID) return true
            }
        }
        val normalized = normalizeAddress(device.address) ?: return false
        return cachedServiceUuids[normalized]?.any { it == SPP_UUID } == true
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val hasConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val hasScan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        return hasConnect && hasScan
    }
}
