package com.js8call.example.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.js8call.core.BluetoothSerialPortCatalog
import com.js8call.core.HamlibRigCatalog
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.example.BuildConfig
import com.js8call.example.R

/**
 * Fragment for app settings.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val prefs = preferenceManager.sharedPreferences
        if (prefs != null && !prefs.contains("my_status")) {
            val statusPref = findPreference<EditTextPreference>("my_status")
            val versionName = try {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                "unknown"
            }
            statusPref?.text = "JS8Android-$versionName"
        }

        val rigModelPref = findPreference<ListPreference>("rig_hamlib_model")
        if (rigModelPref != null) {
            val models = try {
                HamlibRigCatalog.listRigModels()
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = if (models.isNotEmpty()) {
                models.map { it.label }.toTypedArray()
            } else {
                arrayOf(getString(R.string.settings_hamlib_model_none))
            }
            val values = if (models.isNotEmpty()) {
                models.map { it.id }.toTypedArray()
            } else {
                arrayOf("0")
            }
            rigModelPref.entries = entries
            rigModelPref.entryValues = values
        }

        val rigUsbPortPref = findPreference<ListPreference>("rig_hamlib_usb_port")
        if (rigUsbPortPref != null) {
            val usbPorts = try {
                UsbSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }
            val btPorts = try {
                BluetoothSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = ArrayList<String>()
            val values = ArrayList<String>()
            if (usbPorts.isEmpty() && btPorts.isEmpty()) {
                entries.add(getString(R.string.settings_hamlib_usb_port_none))
                values.add("auto")
            } else {
                entries.add(getString(R.string.settings_hamlib_usb_port_auto))
                values.add("auto")
                usbPorts.forEach { port ->
                    entries.add("USB ${port.label}")
                    values.add("android-usb:${port.deviceId}:${port.portIndex}")
                }
                btPorts.forEach { port ->
                    entries.add(port.label)
                    values.add("android-bt:${port.address}:${port.portIndex}")
                }
            }

            val currentValueRaw = prefs?.getString("rig_hamlib_usb_port", "auto") ?: "auto"
            val normalizedValue = normalizeSerialSelection(currentValueRaw, prefs, useLegacyFallback = true)
            if (normalizedValue != currentValueRaw) {
                prefs?.edit()?.putString("rig_hamlib_usb_port", normalizedValue)?.apply()
            }

            if (!values.contains(normalizedValue)) {
                entries.add(getString(R.string.settings_hamlib_usb_port_previous, normalizedValue))
                values.add(normalizedValue)
            }

            rigUsbPortPref.entries = entries.toTypedArray()
            rigUsbPortPref.entryValues = values.toTypedArray()
            rigUsbPortPref.value = normalizedValue

            rigUsbPortPref.setOnPreferenceChangeListener { _, newValue ->
                val selection = normalizeSerialSelection(
                    newValue?.toString().orEmpty(),
                    prefs,
                    useLegacyFallback = false
                )
                updateLegacyUsbPrefs(prefs, selection)
                true
            }
        }
    }

    private fun normalizeSerialSelection(
        selection: String,
        prefs: android.content.SharedPreferences?,
        useLegacyFallback: Boolean
    ): String {
        val trimmed = selection.trim()
        if (trimmed.startsWith("android-usb:") || trimmed.startsWith("android-bt:")) {
            return trimmed
        }
        if (trimmed.isEmpty() || trimmed == "auto") {
            if (!useLegacyFallback) {
                return "auto"
            }
            val deviceId = prefs?.getString("rig_usb_device_id", "")?.toIntOrNull()
            val portIndex = prefs?.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
            return if (deviceId != null) {
                "android-usb:$deviceId:$portIndex"
            } else {
                "auto"
            }
        }
        val parts = trimmed.split(':', limit = 2)
        val deviceId = parts.firstOrNull()?.toIntOrNull()
        return if (deviceId != null) {
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            "android-usb:$deviceId:$portIndex"
        } else {
            trimmed
        }
    }

    private fun updateLegacyUsbPrefs(
        prefs: android.content.SharedPreferences?,
        selection: String
    ) {
        val editor = prefs?.edit() ?: return
        if (selection.startsWith("android-usb:")) {
            val remainder = selection.removePrefix("android-usb:")
            val parts = remainder.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull()
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (deviceId != null) {
                editor.putString("rig_usb_device_id", deviceId.toString())
                editor.putString("rig_usb_port_index", portIndex.toString())
            }
        } else {
            editor.putString("rig_usb_device_id", "")
            editor.putString("rig_usb_port_index", "0")
        }
        editor.apply()
    }
}
