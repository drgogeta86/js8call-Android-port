package com.js8call.example.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
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
            statusPref?.text = "JS8Android ${BuildConfig.VERSION_NAME}"
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
            val ports = try {
                UsbSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = ArrayList<String>()
            val values = ArrayList<String>()
            if (ports.isEmpty()) {
                entries.add(getString(R.string.settings_hamlib_usb_port_none))
                values.add("auto")
            } else {
                entries.add(getString(R.string.settings_hamlib_usb_port_auto))
                values.add("auto")
                ports.forEach { port ->
                    entries.add(port.label)
                    values.add("${port.deviceId}:${port.portIndex}")
                }
            }

            val currentValue = prefs?.getString("rig_hamlib_usb_port", "auto") ?: "auto"
            if (currentValue == "auto") {
                val deviceId = prefs?.getString("rig_usb_device_id", "")?.toIntOrNull()
                val portIndex = prefs?.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
                if (deviceId != null) {
                    val candidate = "${deviceId}:${portIndex}"
                    if (values.contains(candidate)) {
                        rigUsbPortPref.value = candidate
                    }
                }
            } else if (!values.contains(currentValue)) {
                entries.add(getString(R.string.settings_hamlib_usb_port_previous, currentValue))
                values.add(currentValue)
                rigUsbPortPref.value = currentValue
            }

            rigUsbPortPref.entries = entries.toTypedArray()
            rigUsbPortPref.entryValues = values.toTypedArray()

            val deviceIdPref = findPreference<EditTextPreference>("rig_usb_device_id")
            val portIndexPref = findPreference<EditTextPreference>("rig_usb_port_index")
            rigUsbPortPref.setOnPreferenceChangeListener { _, newValue ->
                val selection = newValue?.toString().orEmpty()
                if (selection == "auto") {
                    deviceIdPref?.text = ""
                    portIndexPref?.text = "0"
                } else {
                    val parts = selection.split(':', limit = 2)
                    if (parts.size == 2) {
                        deviceIdPref?.text = parts[0]
                        portIndexPref?.text = parts[1]
                    }
                }
                true
            }
        }
    }
}
