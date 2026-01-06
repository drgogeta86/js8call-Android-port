package com.js8call.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.js8call.core.HamlibRigControl
import com.js8call.core.JS8AudioHelper
import com.js8call.core.JS8Engine
import com.js8call.core.UsbSerialBridge
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.example.MainActivity
import com.js8call.example.R
import java.util.Locale

/**
 * Foreground service for running the JS8 engine in the background.
 *
 * This service manages the native engine lifecycle, audio capture,
 * and broadcasts decode events to the UI.
 */
class JS8EngineService : Service() {

    private var engine: JS8Engine? = null
    private var audioHelper: JS8AudioHelper? = null
    private var rigCtlClient: RigCtlClient? = null
    private var rigCtlConnected: Boolean = false
    private var rigCtlErrorShown: Boolean = false
    private var rigControlMode: String = "none"
    private var hamlibRigControl: HamlibRigControl? = null
    private var hamlibRigConnected: Boolean = false
    private var usbSerialBridge: UsbSerialBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val txMonitorHandler = Handler(Looper.getMainLooper())
    private var selectedAudioDeviceId: Int = -1  // -1 means use default
    private var selectedOutputDeviceId: Int = -1  // -1 means use default
    private var txMonitorActive = false
    private var txMonitorWasAudioActive = false
    private var callsignWarningShown = false
    private var lastTxMessage: String = ""
    private val heartbeatRegex = Regex("^\\s*([^:]+):\\s+@HB\\s+HEARTBEAT\\b", RegexOption.IGNORE_CASE)
    private val heardCallsigns = mutableMapOf<String, Long>()
    private val heardLock = Any()
    private val txMonitorRunnable = object : Runnable {
        override fun run() {
            val activeEngine = engine
            if (activeEngine == null) {
                txMonitorActive = false
                return
            }
            val sessionActive = activeEngine.isTransmitting()
            val audioActive = activeEngine.isTransmittingAudio()
            if (!sessionActive) {
                txMonitorActive = false
                txMonitorWasAudioActive = false
                // Release PTT when TX finishes
                if (isRigControlConnected()) {
                    Thread {
                        val pttOff = setRigPtt(false)
                        Log.i(TAG, "TX finished, PTT released: $pttOff")
                    }.start()
                }
                broadcastTxState(TX_STATE_FINISHED)
                return
            }
            if (audioActive && !txMonitorWasAudioActive) {
                txMonitorWasAudioActive = true
                // Enable PTT when audio TX starts
                if (isRigControlConnected()) {
                    Thread {
                        val pttOn = setRigPtt(true)
                        Log.i(TAG, "TX audio started, PTT enabled: $pttOn")
                    }.start()
                }
                broadcastTxState(TX_STATE_STARTED)
            } else if (!audioActive && txMonitorWasAudioActive) {
                txMonitorWasAudioActive = false
                // Release PTT when audio stops (between packets)
                if (isRigControlConnected()) {
                    Thread {
                        val pttOff = setRigPtt(false)
                        Log.i(TAG, "TX audio paused, PTT released: $pttOff")
                    }.start()
                }
                broadcastTxState(TX_STATE_QUEUED)
            }
            txMonitorHandler.postDelayed(this, TX_MONITOR_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbSerialBridge = UsbSerialBridge(applicationContext)
        hamlibRigControl = HamlibRigControl()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting engine")
                // Get preferred device ID from intent
                if (intent.hasExtra(EXTRA_AUDIO_DEVICE_ID)) {
                    selectedAudioDeviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                    Log.i(TAG, "Start requested with device ID: $selectedAudioDeviceId")
                }
                startForegroundService()
                startEngine()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping engine")
                stopEngine()
                stopSelf()
            }
            ACTION_SWITCH_AUDIO_DEVICE -> {
                val deviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                Log.i(TAG, "Switching audio device to ID: $deviceId")
                switchAudioDevice(deviceId)
            }
            ACTION_SET_FREQUENCY -> {
                val frequencyHz = intent.getLongExtra(EXTRA_FREQUENCY_HZ, 0L)
                Log.i(TAG, "Setting frequency to $frequencyHz Hz")
                setFrequency(frequencyHz)
            }
            ACTION_TRANSMIT_MESSAGE -> {
                handleTransmitMessage(intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        stopEngine()
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JS8EngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startEngine() {
        try {
            // Create callback handler that marshals to main thread
            val callbackHandler = object : JS8Engine.CallbackHandler {
                override fun onDecoded(
                    utc: Int, snr: Int, dt: Float, freq: Float,
                    text: String, type: Int, quality: Float, mode: Int
                ) {
                    Log.d(TAG, "Decoded: $text (SNR: $snr dB)")

                    // Broadcast on main thread
                    mainHandler.post {
                        updateHeardCallsign(text)
                        broadcastDecode(utc, snr, dt, freq, text, type, quality, mode)
                        maybeHandleAutoReply(text, snr, mode)
                    }
                }

                override fun onSpectrum(
                    bins: FloatArray, binHz: Float,
                    powerDb: Float, peakDb: Float
                ) {
                    // Broadcast spectrum data (main thread)
                    mainHandler.post {
                        broadcastSpectrum(bins, binHz, powerDb, peakDb)
                    }
                }

                override fun onDecodeStarted(submodes: Int) {
                    Log.d(TAG, "Decode started: submodes=$submodes")
                    mainHandler.post {
                        broadcastDecodeStarted(submodes)
                    }
                }

                override fun onDecodeFinished(count: Int) {
                    Log.d(TAG, "Decode finished: count=$count")
                    mainHandler.post {
                        broadcastDecodeFinished(count)
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Engine error: $message")
                    mainHandler.post {
                        broadcastError(message)
                    }
                }

                override fun onLog(level: Int, message: String) {
                    val levelStr = when (level) {
                        0 -> "TRACE"
                        1 -> "DEBUG"
                        2 -> "INFO"
                        3 -> "WARN"
                        4 -> "ERROR"
                        else -> "LOG"
                    }
                    Log.d(TAG, "[$levelStr] $message")
                }
            }

            // Create engine
            engine = JS8Engine.create(
                sampleRateHz = 12000,
                submodes = 0x1F, // Enable A/B/C/E/I by default
                callbackHandler = callbackHandler
            )

            // Start engine
            if (engine?.start() == true) {
                Log.i(TAG, "Engine started successfully")

                applyTxBoostSetting()

                updateOutputDeviceForInput(selectedAudioDeviceId)

                // Start audio capture with selected device (if any)
                audioHelper = JS8AudioHelper(engine!!, 12000, selectedAudioDeviceId, applicationContext)
                if (audioHelper?.startCapture() == true) {
                    Log.i(TAG, "Audio capture started")

                    // Detect and broadcast audio device
                    val deviceName = getActiveAudioDevice()
                    broadcastAudioDevice(deviceName)

                    broadcastEngineState(STATE_RUNNING)

                    // Initialize rig control if enabled
                    initializeRigControl()
                } else {
                    Log.e(TAG, "Failed to start audio capture")
                    broadcastError("Failed to start audio capture")
                    broadcastEngineState(STATE_ERROR)
                }
            } else {
                Log.e(TAG, "Failed to start engine")
                broadcastError("Failed to start engine")
                broadcastEngineState(STATE_ERROR)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting engine", e)
            broadcastError("Error starting engine: ${e.message}")
            broadcastEngineState(STATE_ERROR)
        }
    }

    private fun initializeRigControl() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigControlEnabled = prefs.getBoolean("rig_control_enabled", false)
        val rigType = prefs.getString("rig_type", "none")

        if (!rigControlEnabled || rigType == "none") {
            Log.i(TAG, "Rig control not enabled")
            rigControlMode = "none"
            return
        }

        rigControlMode = rigType ?: "none"
        when (rigType) {
            "network" -> initializeNetworkRigControl()
            "hamlib_usb" -> initializeHamlibUsbControl()
            else -> Log.w(TAG, "Unknown rig type: $rigType")
        }
    }

    private fun applyTxBoostSetting() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val txBoostEnabled = prefs.getBoolean("tx_boost_enabled", false)
        engine?.setTxBoostEnabled(txBoostEnabled)
        Log.i(TAG, "TX boost: ${if (txBoostEnabled) "enabled (+10 dB)" else "disabled"}")
    }

    private fun initializeNetworkRigControl() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("rigctld_host", "localhost") ?: "localhost"
        val portStr = prefs.getString("rigctld_port", "4532") ?: "4532"
        val port = portStr.toIntOrNull() ?: 4532

        Log.i(TAG, "Initializing network rig control: $host:$port")

        // Connect on background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                rigCtlClient = RigCtlClient(host, port)
                rigCtlConnected = rigCtlClient?.connect() == true
                rigCtlErrorShown = false

                mainHandler.post {
                    if (rigCtlConnected) {
                        Log.i(TAG, "Connected to rigctld at $host:$port")
                    } else {
                        Log.w(TAG, "Failed to connect to rigctld at $host:$port")
                        broadcastError("Failed to connect to rigctld. Rig control unavailable.")
                        rigCtlErrorShown = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing network rig control", e)
                mainHandler.post {
                    broadcastError("Error connecting to rigctld: ${e.message}")
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun initializeHamlibUsbControl() {
        Log.i(TAG, "Initializing USB rig control (Hamlib)")
        hamlibRigConnected = false

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigModelStr = prefs.getString("rig_hamlib_model", "0")?.trim().orEmpty()
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()
        var deviceIdStr = prefs.getString("rig_usb_device_id", "")?.trim().orEmpty()
        var portIndexStr = prefs.getString("rig_usb_port_index", "0")?.trim().orEmpty()

        if (selectedPort.isNotEmpty() && selectedPort != "auto") {
            val parts = selectedPort.split(':', limit = 2)
            if (parts.size == 2) {
                deviceIdStr = parts[0]
                portIndexStr = parts[1]
            }
        }

        val rigModel = rigModelStr.toIntOrNull() ?: 0
        Log.i(TAG, "Hamlib rig model selected: $rigModel")
        if (rigModel <= 0) {
            Log.w(TAG, "Hamlib rig model not selected")
            broadcastError("Select a Hamlib rig model before enabling USB control.")
            rigCtlErrorShown = true
            return
        }

        usbSerialBridge?.registerNative()

        var deviceId = deviceIdStr.toIntOrNull()
        var portIndex = portIndexStr.toIntOrNull() ?: 0
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            Log.w(TAG, "No USB serial device found for Hamlib")
            broadcastError("No USB serial device found for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        Log.i(TAG, "Hamlib USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for Hamlib device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (granted) {
                    Log.i(TAG, "USB permission granted for Hamlib device")
                    openHamlibRig(rigModel, usbDevice.deviceId, portIndex)
                } else {
                    Log.w(TAG, "USB permission denied for Hamlib device")
                    broadcastError("USB permission denied. Please grant USB access in Settings.")
                    rigCtlErrorShown = true
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for Hamlib device")
            openHamlibRig(rigModel, usbDevice.deviceId, portIndex)
        }
    }

    private fun openHamlibRig(rigModel: Int, deviceId: Int, portIndex: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val baudRate = prefs.getString("rig_serial_baud", "9600")?.toIntOrNull() ?: 9600
        val dataBits = prefs.getString("rig_serial_data_bits", "8")?.toIntOrNull() ?: 8
        val stopBits = prefs.getString("rig_serial_stop_bits", "1")?.toIntOrNull() ?: 1
        val parity = prefs.getString("rig_serial_parity", "none") ?: "none"
        val parityValue = when (parity.lowercase(Locale.US)) {
            "odd" -> 1
            "even" -> 2
            "mark" -> 3
            "space" -> 4
            else -> 0
        }

        Thread {
            val preopenOk = usbSerialBridge?.open(
                deviceId,
                portIndex,
                baudRate,
                dataBits,
                stopBits,
                parityValue
            ) == true

            if (!preopenOk) {
                mainHandler.post {
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    Log.w(TAG, "Hamlib USB pre-open failed for device=$deviceId port=$portIndex")
                    broadcastError("Failed to open USB serial port for Hamlib control.")
                }
                return@Thread
            }

            val resolvedPortIndex = usbSerialBridge?.getActivePortIndex() ?: portIndex

            val ok = hamlibRigControl?.open(
                rigModel,
                deviceId,
                resolvedPortIndex,
                baudRate,
                dataBits,
                stopBits,
                parity
            ) == true

            mainHandler.post {
                hamlibRigConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    Log.i(TAG, "Hamlib rig opened (model=$rigModel device=$deviceId port=$resolvedPortIndex)")
                } else {
                    val detail = hamlibRigControl?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib rig open failed: $detail")
                        broadcastError("Failed to open Hamlib rig: $detail")
                    } else {
                        Log.w(TAG, "Hamlib rig open failed")
                        broadcastError("Failed to open Hamlib rig. Check USB connection and settings.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun stopEngine() {
        try {
            audioHelper?.stopCapture()
            audioHelper?.close()
            audioHelper = null
            stopTxMonitor()

            // Disconnect rig control on background thread
            val networkClientToDisconnect = rigCtlClient
            rigCtlClient = null
            rigCtlConnected = false
            rigCtlErrorShown = false
            hamlibRigConnected = false
            rigControlMode = "none"
            hamlibRigControl?.close()
            usbSerialBridge?.unregisterNative()

            if (networkClientToDisconnect != null) {
                Thread {
                    networkClientToDisconnect.disconnect()
                }.start()
            }

            engine?.stop()
            engine?.close()
            engine = null

            broadcastEngineState(STATE_STOPPED)
            Log.i(TAG, "Engine stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine", e)
        }
    }

    private fun broadcastEngineState(state: String) {
        val intent = Intent(ACTION_ENGINE_STATE).apply {
            putExtra(EXTRA_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecode(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int
    ) {
        val intent = Intent(ACTION_DECODE).apply {
            putExtra(EXTRA_UTC, utc)
            putExtra(EXTRA_SNR, snr)
            putExtra(EXTRA_DT, dt)
            putExtra(EXTRA_FREQ, freq)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_QUALITY, quality)
            putExtra(EXTRA_MODE, mode)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSpectrum(
        bins: FloatArray, binHz: Float,
        powerDb: Float, peakDb: Float
    ) {
        val intent = Intent(ACTION_SPECTRUM).apply {
            putExtra(EXTRA_BINS, bins)
            putExtra(EXTRA_BIN_HZ, binHz)
            putExtra(EXTRA_POWER_DB, powerDb)
            putExtra(EXTRA_PEAK_DB, peakDb)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecodeStarted(submodes: Int) {
        val intent = Intent(ACTION_DECODE_STARTED).apply {
            putExtra(EXTRA_SUBMODES, submodes)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecodeFinished(count: Int) {
        val intent = Intent(ACTION_DECODE_FINISHED).apply {
            putExtra(EXTRA_COUNT, count)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastAudioDevice(deviceName: String) {
        val intent = Intent(ACTION_AUDIO_DEVICE).apply {
            putExtra(EXTRA_AUDIO_DEVICE, deviceName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastRadioFrequency(frequencyHz: Long) {
        val intent = Intent(ACTION_RADIO_FREQUENCY).apply {
            putExtra(EXTRA_RADIO_FREQUENCY_HZ, frequencyHz)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getActiveAudioDevice(): String {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // For Android M (API 23) and above, use AudioDeviceInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // If a specific device is selected, find it
            if (selectedAudioDeviceId != -1) {
                for (device in devices) {
                    if (device.id == selectedAudioDeviceId) {
                        val deviceType = getDeviceName(device)
                        Log.i(TAG, "Using selected audio device: $deviceType (ID: ${device.id})")
                        return deviceType
                    }
                }
            }

            // Find the first active input device
            for (device in devices) {
                val deviceType = getDeviceName(device)
                // Return first valid device (Oboe typically uses default)
                Log.i(TAG, "Detected audio device: $deviceType")
                return deviceType
            }
        }

        // Fallback for older Android versions or if no device found
        return "Microphone"
    }

    private fun updateOutputDeviceForInput(inputDeviceId: Int) {
        val outputId = findOutputDeviceId(inputDeviceId)
        selectedOutputDeviceId = outputId
        engine?.setOutputDevice(outputId)
        if (outputId > 0) {
            Log.i(TAG, "Using output device: ${getOutputDeviceName(outputId)} (ID: $outputId)")
        } else {
            Log.i(TAG, "Using default output device")
        }
    }

    private fun findOutputDeviceId(inputDeviceId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return -1
        if (inputDeviceId == -1) return -1

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == inputDeviceId } ?: return -1

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputName = inputDevice.productName?.toString()?.takeIf { it.isNotBlank() }
        val inputFamily = deviceFamily(inputDevice.type)

        var output = if (inputName != null) {
            outputs.firstOrNull { device ->
                device.productName?.toString() == inputName &&
                    (inputFamily.isEmpty() || deviceFamily(device.type) == inputFamily)
            }
        } else {
            null
        }

        if (output == null && inputName != null) {
            output = outputs.firstOrNull { device -> device.productName?.toString() == inputName }
        }

        if (output == null && inputFamily.isNotEmpty()) {
            output = outputs.firstOrNull { device -> deviceFamily(device.type) == inputFamily }
        }

        return output?.id ?: -1
    }

    private fun deviceFamily(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
            else -> ""
        }
    }

    private fun getOutputDeviceName(deviceId: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Default Output"
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = outputs.firstOrNull { it.id == deviceId } ?: return "Default Output"
        return getDeviceName(device)
    }

    private fun getDeviceName(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> {
                // Try to get product name for USB devices
                device.productName?.toString() ?: "USB Audio Device"
            }
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
            else -> "Unknown Device"
        }
    }

    private fun switchAudioDevice(deviceId: Int) {
        try {
            // Store the selected device ID
            selectedAudioDeviceId = deviceId

            // If audio helper exists, just switch the device without restarting
            audioHelper?.let { helper ->
                helper.setPreferredDevice(deviceId)
                Log.i(TAG, "Switched audio device to ID: $deviceId")

                // Detect and broadcast the new device
                val deviceName = getActiveAudioDevice()
                broadcastAudioDevice(deviceName)
                updateOutputDeviceForInput(deviceId)
                return
            }

            // If no audio helper, create one with the new device
            if (engine != null) {
                audioHelper = JS8AudioHelper(engine!!, 12000, deviceId, applicationContext)
                if (audioHelper?.startCapture() == true) {
                    Log.i(TAG, "Audio capture started with device ID: $deviceId")

                    val deviceName = getActiveAudioDevice()
                    broadcastAudioDevice(deviceName)
                    updateOutputDeviceForInput(deviceId)
                } else {
                    Log.e(TAG, "Failed to start audio capture")
                    broadcastError("Failed to switch audio device")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error switching audio device", e)
            broadcastError("Error switching audio device: ${e.message}")
        }
    }

    private fun handleTransmitMessage(intent: Intent) {
        val activeEngine = engine
        if (activeEngine == null) {
            broadcastError("Engine not running")
            return
        }

        val text = intent.getStringExtra(EXTRA_TX_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            broadcastError("Empty TX message")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val directed = intent.getStringExtra(EXTRA_TX_DIRECTED)?.trim().orEmpty()
        val submode = intent.getIntExtra(EXTRA_TX_SUBMODE, 0)
        val audioFrequencyHz = intent.getDoubleExtra(EXTRA_TX_FREQ_HZ, DEFAULT_AUDIO_FREQUENCY_HZ)
        val txDelaySec = intent.getDoubleExtra(EXTRA_TX_DELAY_S, 0.0)
        val forceIdentify = intent.getBooleanExtra(EXTRA_TX_FORCE_IDENTIFY, false)
        val forceData = intent.getBooleanExtra(EXTRA_TX_FORCE_DATA, false)
        val effectiveForceIdentify = forceIdentify || callsign.isNotBlank()
        val payloadText = applyGridIfHeartbeat(text, grid)

        Log.i(
            TAG,
            "TX request: text_len=${payloadText.length}, directed='${directed}', submode=$submode, freq=$audioFrequencyHz, delay=$txDelaySec, identify=$effectiveForceIdentify"
        )

        val ok = activeEngine.transmitMessage(
            text = payloadText,
            myCall = callsign,
            myGrid = grid,
            selectedCall = directed,
            submode = submode,
            audioFrequencyHz = audioFrequencyHz,
            txDelaySec = txDelaySec,
            forceIdentify = effectiveForceIdentify,
            forceData = forceData
        )

        if (ok) {
            Log.i(TAG, "TX request accepted")
            updateLastTxMessage(payloadText, directed)
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor()
        } else {
            Log.e(TAG, "TX request rejected")
            broadcastError("Failed to start transmit")
            broadcastTxState(TX_STATE_FAILED)
        }
    }

    private fun broadcastTxState(state: String) {
        val intent = Intent(ACTION_TX_STATE).apply {
            putExtra(EXTRA_TX_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startTxMonitor() {
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        txMonitorActive = true
        txMonitorWasAudioActive = false
        txMonitorHandler.postDelayed(txMonitorRunnable, TX_MONITOR_INTERVAL_MS)
    }

    private fun stopTxMonitor() {
        if (!txMonitorActive) return
        txMonitorActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
    }

    private fun maybeHandleAutoReply(text: String, snr: Int, mode: Int) {
        if (!isAutoreplyEnabled()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        if (isTransmitActive()) return

        val heartbeat = parseHeartbeat(text)
        if (heartbeat != null) {
            if (isSelfCallsign(callsign, heartbeat.from)) return
            val snrText = formatSNR(snr)
            if (snrText.isEmpty()) return
            val submode = if (mode >= 0) mode else 0
            val target = heartbeat.from.trim().uppercase()
            val payload = "HEARTBEAT SNR $snrText"
            Log.i(TAG, "Auto HB ACK: from=$target snr=$snrText text='$payload'")
            sendAutoReply(payload, target, submode)
            return
        }

        val directed = parseDirectedCommand(text) ?: return
        if (!shouldReplyToDirected(callsign, directed)) return
        val submode = if (mode >= 0) mode else 0
        when (directed.command.uppercase()) {
            "SNR?", "?" -> {
                val snrText = formatSNR(snr)
                if (snrText.isEmpty()) return
                Log.i(TAG, "Auto SNR reply: from=${directed.from} snr=$snrText")
                sendAutoReply("SNR $snrText", directed.from, submode)
            }
            "INFO?" -> {
                val info = prefs.getString(PREF_MY_INFO, "")?.trim().orEmpty()
                if (info.isBlank()) return
                Log.i(TAG, "Auto INFO reply: from=${directed.from}")
                sendAutoReply("INFO $info", directed.from, submode)
            }
            "STATUS?" -> {
                val status = prefs.getString(PREF_MY_STATUS, "")?.trim().orEmpty()
                if (status.isBlank()) return
                Log.i(TAG, "Auto STATUS reply: from=${directed.from}")
                sendAutoReply("STATUS $status", directed.from, submode)
            }
            "HEARING?" -> {
                val heard = getRecentHeardCallsigns(
                    exclude = setOf(directed.from.trim().uppercase(), callsign),
                    limit = HEARD_LIMIT
                )
                if (heard.isEmpty()) return
                Log.i(TAG, "Auto HEARING reply: from=${directed.from}, count=${heard.size}")
                sendAutoReply("HEARING ${heard.joinToString(" ")}", directed.from, submode)
            }
            "GRID?" -> {
                val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
                if (grid.isBlank()) return
                Log.i(TAG, "Auto GRID reply: from=${directed.from}")
                sendAutoReply("GRID $grid", directed.from, submode)
            }
            "AGN?" -> {
                val message = lastTxMessage.trimEnd()
                if (message.isBlank()) return
                Log.i(TAG, "Auto AGN reply: from=${directed.from}")
                sendAutoReply(message, null, submode, requireDirected = false)
            }
            else -> return
        }
    }

    private fun parseHeartbeat(text: String): Heartbeat? {
        val match = heartbeatRegex.find(text) ?: return null
        val from = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (from.isBlank()) return null
        return Heartbeat(from)
    }

    private data class Heartbeat(val from: String)

    private fun parseDirectedCommand(text: String): DirectedCommand? {
        val parts = text.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 3) return null
        val from = parts[0].trim().trimEnd(':')
        val to = parts[1].trim()
        val command = parts[2].trim()
        if (from.isBlank() || to.isBlank() || command.isBlank()) return null
        return DirectedCommand(from, to, command)
    }

    private data class DirectedCommand(val from: String, val to: String, val command: String)

    private fun shouldReplyToDirected(myCall: String, command: DirectedCommand): Boolean {
        if (command.to.startsWith("@")) return false
        if (!isSelfCallsign(myCall, command.to)) return false
        if (isSelfCallsign(myCall, command.from)) return false
        return true
    }

    private fun isAutoreplyEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_AUTOREPLY_ENABLED, false)
    }

    private fun getConfiguredCallsign(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty().uppercase()
        if (callsign.isBlank()) return null
        callsignWarningShown = false
        return callsign
    }

    private fun warnMissingCallsign() {
        if (callsignWarningShown) return
        callsignWarningShown = true
        broadcastError(getString(R.string.error_callsign_required))
    }

    private fun isTransmitActive(): Boolean {
        val activeEngine = engine ?: return false
        return activeEngine.isTransmitting() || activeEngine.isTransmittingAudio()
    }

    private fun isSelfCallsign(myCall: String, from: String): Boolean {
        val mine = myCall.trim().uppercase()
        val theirs = from.trim().uppercase()
        if (mine == theirs) return true
        val mineParts = mine.split("/")
        val theirParts = theirs.split("/")
        return mineParts.any { it.isNotBlank() && theirParts.contains(it) }
    }

    private fun formatSNR(snr: Int): String {
        if (snr < -60 || snr > 60) return ""
        val sign = if (snr >= 0) "+" else ""
        val width = if (snr < 0) 3 else 2
        return String.format("%s%0${width}d", sign, snr)
    }

    private fun sendAutoReply(
        text: String,
        directed: String?,
        submode: Int,
        requireDirected: Boolean = true,
        forceData: Boolean = false
    ) {
        val activeEngine = engine ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val payloadText = text.trim()
        val directedCall = directed?.trim().orEmpty().uppercase()
        if (requireDirected && directedCall.isBlank()) return

        val ok = activeEngine.transmitMessage(
            text = payloadText,
            myCall = callsign,
            myGrid = grid,
            selectedCall = directedCall,
            submode = submode,
            audioFrequencyHz = DEFAULT_AUDIO_FREQUENCY_HZ,
            txDelaySec = 0.0,
            forceIdentify = callsign.isNotBlank(),
            forceData = forceData
        )

        if (ok) {
            Log.i(TAG, "Autoreply queued: to=$directedCall text='$payloadText'")
            updateLastTxMessage(payloadText, directedCall)
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor()
        } else {
            Log.e(TAG, "Autoreply rejected")
            broadcastError("Failed to start transmit")
            broadcastTxState(TX_STATE_FAILED)
        }
    }

    private fun updateHeardCallsign(text: String) {
        val callsign = extractHeardCallsign(text) ?: return
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            heardCallsigns[callsign] = now
            pruneHeardEntries(now)
        }
    }

    private fun getRecentHeardCallsigns(exclude: Set<String>, limit: Int): List<String> {
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            pruneHeardEntries(now)
            return heardCallsigns.entries
                .asSequence()
                .filter { !exclude.contains(it.key) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
                .toList()
        }
    }

    private fun pruneHeardEntries(now: Long) {
        val iter = heardCallsigns.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > HEARD_WINDOW_MS) {
                iter.remove()
            }
        }
    }

    private fun extractHeardCallsign(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val firstToken = trimmed.split(Regex("\\s+"), limit = 2)[0]
        var token = firstToken.trimEnd(':').uppercase()
        if (token.contains(">")) {
            token = token.substringBefore(">").trimEnd(':')
        }
        if (token.startsWith("@")) return null
        if (token in HEARD_EXCLUDE_TOKENS) return null
        val callsignRegex = Regex("^[A-Z0-9/]{3,12}$")
        if (!callsignRegex.matches(token)) return null
        if (!token.any { it.isLetter() } || !token.any { it.isDigit() }) return null
        return token
    }

    private fun updateLastTxMessage(text: String, directedCall: String) {
        val built = buildTxMessage(text, directedCall)
        if (built.isNotBlank()) {
            lastTxMessage = built
        }
    }

    private fun buildTxMessage(text: String, directedCall: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val selected = directedCall.trim().uppercase()
        if (selected.isEmpty()) return trimmed
        if (trimmed.startsWith("`")) return trimmed

        val upper = trimmed.uppercase()
        val lineStartsWithBase = upper.startsWith("@ALLCALL") ||
            upper.startsWith("CQ") ||
            upper.startsWith("HB") ||
            upper.startsWith("HEARTBEAT")
        if (lineStartsWithBase) return trimmed
        if (trimmed.startsWith(selected, ignoreCase = true)) return trimmed

        val sep = if (trimmed.startsWith(" ")) "" else " "
        return selected + sep + trimmed
    }

    private fun applyGridIfHeartbeat(text: String, grid: String): String {
        val trimmed = text.trim()
        if (grid.length < 4) return trimmed
        val grid4 = grid.substring(0, 4).uppercase()
        val upper = trimmed.uppercase()
        val isHeartbeat = upper.startsWith("CQ") || upper.startsWith("HB") || upper.startsWith("HEARTBEAT")
        if (!isHeartbeat) return trimmed
        val gridRegex = Regex("\\b[A-R]{2}[0-9]{2}\\b", RegexOption.IGNORE_CASE)
        if (gridRegex.containsMatchIn(trimmed)) return trimmed
        return "$trimmed $grid4".trim()
    }

    private fun isRigControlConnected(): Boolean {
        return when (rigControlMode) {
            "network" -> rigCtlConnected
            "hamlib_usb" -> hamlibRigConnected
            else -> false
        }
    }

    private fun setRigPtt(enabled: Boolean): Boolean {
        return when (rigControlMode) {
            "network" -> rigCtlClient?.setPtt(enabled) == true
            "hamlib_usb" -> hamlibRigControl?.setPtt(enabled) == true
            else -> false
        }
    }

    private fun setFrequency(frequencyHz: Long) {
        if (!isRigControlConnected()) {
            Log.d(TAG, "Cannot set frequency: rig control not connected")
            return
        }

        // Run on background thread
        Thread {
            val success = when (rigControlMode) {
                "network" -> rigCtlClient?.setFrequency(frequencyHz) == true
                "hamlib_usb" -> hamlibRigControl?.setFrequency(frequencyHz) == true
                else -> false
            }

            mainHandler.post {
                if (success) {
                    Log.i(TAG, "Frequency set to $frequencyHz Hz")
                } else {
                    val detail = if (rigControlMode == "hamlib_usb") {
                        hamlibRigControl?.getLastError().orEmpty()
                    } else {
                        ""
                    }
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz: $detail")
                    } else {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz")
                    }
                    if (!rigCtlErrorShown) {
                        if (detail.isNotBlank()) {
                            broadcastError("Rig control failed: $detail")
                        } else {
                            broadcastError("Rig control communication failed")
                        }
                        rigCtlErrorShown = true
                    }
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "JS8EngineService"
        private const val PREF_AUTOREPLY_ENABLED = "autoreply_enabled"
        private const val PREF_MY_INFO = "my_info"
        private const val PREF_MY_STATUS = "my_status"
        private const val HEARD_LIMIT = 4
        private const val HEARD_WINDOW_MS = 15 * 60 * 1000L
        private val HEARD_EXCLUDE_TOKENS = setOf("CQ", "HB", "HEARTBEAT", "ALLCALL", "@ALLCALL")

        // Actions
        const val ACTION_START = "com.js8call.example.ACTION_START"
        const val ACTION_STOP = "com.js8call.example.ACTION_STOP"
        const val ACTION_SWITCH_AUDIO_DEVICE = "com.js8call.example.ACTION_SWITCH_AUDIO_DEVICE"
        const val ACTION_SET_FREQUENCY = "com.js8call.example.ACTION_SET_FREQUENCY"
        const val ACTION_ENGINE_STATE = "com.js8call.example.ACTION_ENGINE_STATE"
        const val ACTION_DECODE = "com.js8call.example.ACTION_DECODE"
        const val ACTION_SPECTRUM = "com.js8call.example.ACTION_SPECTRUM"
        const val ACTION_DECODE_STARTED = "com.js8call.example.ACTION_DECODE_STARTED"
        const val ACTION_DECODE_FINISHED = "com.js8call.example.ACTION_DECODE_FINISHED"
        const val ACTION_AUDIO_DEVICE = "com.js8call.example.ACTION_AUDIO_DEVICE"
        const val ACTION_ERROR = "com.js8call.example.ACTION_ERROR"
        const val ACTION_TRANSMIT_MESSAGE = "com.js8call.example.ACTION_TRANSMIT_MESSAGE"
        const val ACTION_TX_STATE = "com.js8call.example.ACTION_TX_STATE"
        const val ACTION_RADIO_FREQUENCY = "com.js8call.example.ACTION_RADIO_FREQUENCY"

        // Engine states
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_ERROR = "error"

        // Extras
        const val EXTRA_STATE = "state"
        const val EXTRA_UTC = "utc"
        const val EXTRA_SNR = "snr"
        const val EXTRA_DT = "dt"
        const val EXTRA_FREQ = "freq"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MODE = "mode"
        const val EXTRA_BINS = "bins"
        const val EXTRA_BIN_HZ = "bin_hz"
        const val EXTRA_POWER_DB = "power_db"
        const val EXTRA_PEAK_DB = "peak_db"
        const val EXTRA_SUBMODES = "submodes"
        const val EXTRA_COUNT = "count"
        const val EXTRA_AUDIO_DEVICE = "audio_device"
        const val EXTRA_AUDIO_DEVICE_ID = "audio_device_id"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_FREQUENCY_HZ = "frequency_hz"
        const val EXTRA_TX_TEXT = "tx_text"
        const val EXTRA_TX_DIRECTED = "tx_directed"
        const val EXTRA_TX_SUBMODE = "tx_submode"
        const val EXTRA_TX_FREQ_HZ = "tx_freq_hz"
        const val EXTRA_TX_DELAY_S = "tx_delay_s"
        const val EXTRA_TX_FORCE_IDENTIFY = "tx_force_identify"
        const val EXTRA_TX_FORCE_DATA = "tx_force_data"
        const val EXTRA_TX_STATE = "tx_state"
        const val EXTRA_RADIO_FREQUENCY_HZ = "radio_frequency_hz"

        const val TX_STATE_QUEUED = "queued"
        const val TX_STATE_STARTED = "started"
        const val TX_STATE_FINISHED = "finished"
        const val TX_STATE_FAILED = "failed"

        const val DEFAULT_AUDIO_FREQUENCY_HZ = 1500.0
        private const val TX_MONITOR_INTERVAL_MS = 250L

        private const val CHANNEL_ID = "js8call_service"
        private const val NOTIFICATION_ID = 1
    }
}
