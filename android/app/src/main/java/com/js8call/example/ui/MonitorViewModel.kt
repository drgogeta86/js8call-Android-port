package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.js8call.example.model.EngineState
import com.js8call.example.model.MonitorStatus
import com.js8call.example.model.SpectrumData

/**
 * ViewModel for the Monitor screen.
 * Manages engine state and monitoring status.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val _status = MutableLiveData<MonitorStatus>()
    val status: LiveData<MonitorStatus> = _status

    private val _spectrum = MutableLiveData<SpectrumData>()
    val spectrum: LiveData<SpectrumData> = _spectrum

    private val _isRunning = MutableLiveData<Boolean>(false)
    val isRunning: LiveData<Boolean> = _isRunning

    init {
        _status.value = MonitorStatus(state = EngineState.STOPPED)
    }

    /**
     * Start monitoring.
     */
    fun startMonitoring() {
        _status.value = _status.value?.copy(state = EngineState.STARTING)
        // Service will be started by fragment
    }

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        _status.value = _status.value?.copy(state = EngineState.STOPPED)
        _isRunning.value = false
        // Service will be stopped by fragment
    }

    /**
     * Update engine state (called from service callbacks).
     */
    fun updateState(state: EngineState, errorMessage: String? = null) {
        _status.value = _status.value?.copy(
            state = state,
            errorMessage = errorMessage
        )
        _isRunning.value = (state == EngineState.RUNNING)
    }

    /**
     * Update spectrum data from engine.
     */
    fun updateSpectrum(bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float) {
        _spectrum.value = SpectrumData(bins, binHz, powerDb, peakDb)

        // Update power levels in status
        _status.value = _status.value?.copy(
            powerDb = powerDb,
            peakDb = peakDb
        )
    }

    /**
     * Update SNR value.
     */
    fun updateSnr(snr: Int) {
        _status.value = _status.value?.copy(snr = snr)
    }

    /**
     * Update frequency.
     */
    fun updateFrequency(frequency: Long) {
        _status.value = _status.value?.copy(frequency = frequency)
    }

    /**
     * Update audio device name.
     */
    fun updateAudioDevice(deviceName: String) {
        _status.value = _status.value?.copy(audioDevice = deviceName)
    }

    /**
     * Handle error.
     */
    fun onError(message: String) {
        _status.value = _status.value?.copy(
            state = EngineState.ERROR,
            errorMessage = message
        )
        _isRunning.value = false
    }
}
