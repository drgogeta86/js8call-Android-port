package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.js8call.example.model.DecodedMessage
import com.js8call.example.model.MessageBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ViewModel for the Decodes screen.
 * Manages the list of decoded messages.
 */
class DecodeViewModel(application: Application) : AndroidViewModel(application) {

    private val _decodes = MutableLiveData<List<DecodedMessage>>(emptyList())
    val decodes: LiveData<List<DecodedMessage>> = _decodes

    private val _filterText = MutableLiveData<String>("")
    val filterText: LiveData<String> = _filterText

    private val allDecodes = mutableListOf<DecodedMessage>()
    private val maxDecodes = 1000 // Keep last 1000 decodes

    // Message buffering for multipart messages
    private val messageBuffers = mutableMapOf<Int, MessageBuffer>()
    private val bufferTimeoutMs = 90_000L // 90 seconds
    private val frequencyToleranceHz = 1.0f // Tolerance for frequency matching

    /**
     * Add a new decoded message.
     */
    fun addDecode(message: DecodedMessage) {
        allDecodes.add(0, message) // Add to beginning

        // Limit size
        if (allDecodes.size > maxDecodes) {
            allDecodes.removeAt(allDecodes.size - 1)
        }

        applyFilter()
    }

    /**
     * Add a decoded message from engine callback.
     */
    fun addDecode(
        utc: Int,
        snr: Int,
        dt: Float,
        freq: Float,
        text: String,
        type: Int,
        quality: Float,
        mode: Int
    ) {
        val message = DecodedMessage(utc, snr, dt, freq, text, type, quality, mode)

        // Clean up stale buffers before processing new message
        cleanupStaleBuffers()

        // Find matching buffer within frequency tolerance
        val matchingBufferKey = findMatchingBufferKey(freq)

        // Handle complete single-frame messages (first + last)
        if (message.isFirstFrame() && message.isLastFrame()) {
            if (matchingBufferKey != null) {
                messageBuffers.remove(matchingBufferKey)
            }
            addDecodeToDisplay(message)
            return
        }

        // Handle first frame (clear existing buffer and create new one)
        if (message.isFirstFrame()) {
            // Remove any existing buffer at matching frequency
            if (matchingBufferKey != null) {
                messageBuffers.remove(matchingBufferKey)
            }
            // Create new buffer with current frequency as key
            val newKey = freq.roundToInt()
            messageBuffers[newKey] = MessageBuffer(
                frames = mutableListOf(message),
                firstTimestamp = message.timestamp,
                frequencyKey = newKey
            )
            return
        }

        // Handle middle/last frames (including unflagged frames when buffering)
        if (matchingBufferKey != null) {
            val buffer = messageBuffers[matchingBufferKey]!!
            // Add frame to existing buffer
            buffer.frames.add(message)

            // If this is the last frame, assemble and display
            if (message.isLastFrame()) {
                val assembled = assembleMessage(buffer)
                addDecodeToDisplay(assembled)
                messageBuffers.remove(matchingBufferKey)
            }
            return
        }

        // Handle single-frame messages (display immediately)
        if (message.isSingleFrame()) {
            addDecodeToDisplay(message)
            return
        } else {
            // No buffer exists - this might be a missed first frame
            // Create a buffer anyway and add this frame
            val newKey = freq.roundToInt()
            messageBuffers[newKey] = MessageBuffer(
                frames = mutableListOf(message),
                firstTimestamp = message.timestamp,
                frequencyKey = newKey
            )

            // If it's also a last frame, assemble immediately
            if (message.isLastFrame()) {
                val assembled = assembleMessage(messageBuffers[newKey]!!)
                addDecodeToDisplay(assembled)
                messageBuffers.remove(newKey)
            }
        }
    }

    /**
     * Add a decoded message directly to the display list.
     */
    private fun addDecodeToDisplay(message: DecodedMessage) {
        allDecodes.add(0, message) // Add to beginning

        // Limit size
        if (allDecodes.size > maxDecodes) {
            allDecodes.removeAt(allDecodes.size - 1)
        }

        applyFilter()
    }

    /**
     * Assemble a complete message from buffered frames.
     */
    private fun assembleMessage(buffer: MessageBuffer): DecodedMessage {
        // Concatenate all frame texts
        val assembledText = buffer.frames.joinToString("") { it.text }

        // Use the last frame's metadata (most recent)
        val lastFrame = buffer.frames.last()

        return DecodedMessage(
            utc = lastFrame.utc,
            snr = lastFrame.snr,
            dt = lastFrame.dt,
            frequency = lastFrame.frequency,
            text = assembledText,
            type = lastFrame.type,
            quality = lastFrame.quality,
            mode = lastFrame.mode,
            timestamp = lastFrame.timestamp
        )
    }

    /**
     * Find a buffer key that matches the given frequency within tolerance.
     * Returns null if no matching buffer is found.
     */
    private fun findMatchingBufferKey(freq: Float): Int? {
        for ((key, _) in messageBuffers) {
            // Check if frequency is within tolerance of buffer's frequency key
            if (kotlin.math.abs(freq - key) <= frequencyToleranceHz) {
                return key
            }
        }
        return null
    }

    /**
     * Clean up stale message buffers (older than timeout).
     */
    private fun cleanupStaleBuffers() {
        val currentTime = System.currentTimeMillis()
        val keysToRemove = mutableListOf<Int>()

        for ((key, buffer) in messageBuffers) {
            val age = currentTime - buffer.firstTimestamp
            if (age > bufferTimeoutMs) {
                // Buffer timed out - display incomplete message
                val assembled = assembleMessage(buffer)
                addDecodeToDisplay(assembled)
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { messageBuffers.remove(it) }
    }

    /**
     * Clear all decodes.
     */
    fun clearDecodes() {
        allDecodes.clear()
        _decodes.value = emptyList()
    }

    /**
     * Set filter text.
     */
    fun setFilter(text: String) {
        _filterText.value = text
        applyFilter()
    }

    /**
     * Apply current filter to decode list.
     */
    private fun applyFilter() {
        val filter = _filterText.value ?: ""
        val filtered = if (filter.isBlank()) {
            allDecodes.toList()
        } else {
            allDecodes.filter { decode ->
                decode.text.contains(filter, ignoreCase = true)
            }
        }
        _decodes.value = filtered
    }

    /**
     * Get decode count.
     */
    fun getDecodeCount(): Int = allDecodes.size

    /**
     * Export decodes as text.
     */
    fun exportDecodes(): String {
        return allDecodes.joinToString("\n") { it.toDisplayString() }
    }
}
