package com.js8call.core

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.Manifest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper class to integrate Android AudioRecord with JS8Engine.
 *
 * This handles the Android audio capture API and feeds samples to the native engine.
 */
class JS8AudioHelper(
    private val engine: JS8Engine,
    private val targetSampleRate: Int = 12000,  // Target rate for engine
    private var preferredDeviceId: Int = -1,  // -1 means use default
    private var context: Context? = null
) : AutoCloseable {

    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: HandlerThread? = null
    private var isRecording = false

    // Capture at 48kHz (native USB rate) and downsample natively to target rate
    private var captureSampleRate = 48000
    private var actualSampleRate = captureSampleRate

    /**
     * Set preferred audio input device.
     * Call before startCapture() or while recording.
     */
    fun setPreferredDevice(deviceId: Int) {
        preferredDeviceId = deviceId

        // If already recording, update the device
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && context != null) {
            audioRecord?.let { record ->
                if (deviceId >= 0) {
                    val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val device = devices.find { it.id == deviceId }
                    if (device != null) {
                        val success = record.setPreferredDevice(device)
                        android.util.Log.i("JS8AudioHelper",
                            "Set preferred device: ${device.productName} (ID: $deviceId), success=$success")
                    } else {
                        android.util.Log.w("JS8AudioHelper",
                            "Device ID $deviceId not found")
                    }
                } else {
                    record.setPreferredDevice(null)  // Use default
                }
            }
        }
    }

    /**
     * Start audio capture and feed to engine.
     *
     * @return true if capture started successfully
     */
    fun startCapture(): Boolean {
        if (isRecording) return false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val safeContext = context ?: return false
            if (safeContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("JS8AudioHelper", "RECORD_AUDIO permission not granted")
                return false
            }
        }

        try {
            val candidates = listOf(48000, 44100)
            var bufferSize = 0
            for (rate in candidates) {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    rate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    continue
                }
                // Use 2x minimum buffer size for balance between latency and reliability
                bufferSize = minBufferSize * 2
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    captureSampleRate = rate
                    break
                } else {
                    record.release()
                }
            }

            if (audioRecord == null) {
                return false
            }

            // Set preferred device if specified (API 23+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && preferredDeviceId >= 0 && context != null) {
                val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val device = devices.find { it.id == preferredDeviceId }
                if (device != null) {
                    val success = audioRecord?.setPreferredDevice(device) ?: false
                    android.util.Log.i("JS8AudioHelper",
                        "Set preferred device on startup: ${device.productName} (ID: $preferredDeviceId), success=$success")
                } else {
                    android.util.Log.w("JS8AudioHelper",
                        "Preferred device ID $preferredDeviceId not found, using default")
                }
            }

            // Enable low-latency mode if available (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    // Request low-latency performance mode for better timing accuracy
                    audioRecord?.setPreferredDevice(audioRecord?.preferredDevice)
                } catch (e: Exception) {
                    android.util.Log.w("JS8AudioHelper", "Could not set performance mode: ${e.message}")
                }
            }

            audioRecord?.startRecording()
            isRecording = true

            // Record the actual sample rate returned by the device. Some devices may not honor
            // the requested rate, so use the real value when handing data to native.
            actualSampleRate = audioRecord?.sampleRate ?: captureSampleRate
            val resampleRatio = actualSampleRate.toDouble() / targetSampleRate.toDouble()

            android.util.Log.i("JS8AudioHelper",
                "Audio capture started: requested $captureSampleRate Hz, actual $actualSampleRate Hz, " +
                "target $targetSampleRate Hz, handing raw audio to native downsampler " +
                "(resample ratio: ${"%.3f".format(resampleRatio)}), buffer size: $bufferSize samples")

            if (actualSampleRate % targetSampleRate != 0) {
                android.util.Log.i(
                    "JS8AudioHelper",
                    "Actual sample rate $actualSampleRate not divisible by target $targetSampleRate; " +
                        "using fractional resampler"
                )
            }

            // Start recording thread
            recordingThread = HandlerThread("JS8AudioCapture").apply {
                start()
                Handler(looper).post { recordLoop() }
            }

            return true
        } catch (e: SecurityException) {
            // Permission not granted
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * Stop audio capture.
     */
    fun stopCapture() {
        isRecording = false

        recordingThread?.quitSafely()
        recordingThread?.join()
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun close() {
        stopCapture()
    }

    private fun recordLoop() {
        val recorder = audioRecord ?: return
        val bufferSize = 4096  // Process in 4K chunks at 48kHz
        val buffer = ShortArray(bufferSize)
        var totalSamples = 0L
        var lastLogTime = System.currentTimeMillis()

        while (isRecording) {
            val samplesRead = recorder.read(buffer, 0, bufferSize)

            if (samplesRead > 0) {
                val timestamp = System.nanoTime()
                val success = engine.submitAudioRaw(buffer, samplesRead, actualSampleRate, timestamp)

                totalSamples += samplesRead

                // Log every second
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val sampleRate = totalSamples / ((now - lastLogTime) / 1000f)
                    android.util.Log.d("JS8AudioHelper",
                        "Audio: $samplesRead samples @ ${captureSampleRate}Hz (raw), " +
                        "success=$success, rate=${sampleRate.toInt()} samples/sec, " +
                        "first3=[${buffer[0]}, ${buffer[1]}, ${buffer[2]}]")
                    lastLogTime = now
                    totalSamples = 0
                }
            } else if (samplesRead < 0) {
                // Error occurred
                when (samplesRead) {
                    AudioRecord.ERROR_INVALID_OPERATION,
                    AudioRecord.ERROR_BAD_VALUE,
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        isRecording = false
                        break
                    }
                }
            }
        }
    }
}

/**
 * Helper to marshal native callbacks to Android UI thread.
 */
class UIThreadCallbackAdapter(
    private val handler: Handler,
    private val delegate: JS8Engine.CallbackHandler
) : JS8Engine.CallbackHandler {

    override fun onDecoded(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int
    ) {
        handler.post {
            delegate.onDecoded(utc, snr, dt, freq, text, type, quality, mode)
        }
    }

    override fun onSpectrum(bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float) {
        handler.post {
            delegate.onSpectrum(bins, binHz, powerDb, peakDb)
        }
    }

    override fun onDecodeStarted(submodes: Int) {
        handler.post {
            delegate.onDecodeStarted(submodes)
        }
    }

    override fun onDecodeFinished(count: Int) {
        handler.post {
            delegate.onDecodeFinished(count)
        }
    }

    override fun onError(message: String) {
        handler.post {
            delegate.onError(message)
        }
    }

    override fun onLog(level: Int, message: String) {
        handler.post {
            delegate.onLog(level, message)
        }
    }
}
