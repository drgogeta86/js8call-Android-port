package com.js8call.example.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Icom CI-V protocol client for USB serial communication.
 *
 * CI-V Protocol Format:
 * 0xFE 0xFE radio_addr 0xE0 cmd data... 0xFD
 *
 * Response Format:
 * 0xFE 0xFE 0xE0 radio_addr cmd data... 0xFD
 * 0xFE 0xFE 0xE0 radio_addr 0xFB 0xFD  (OK)
 * 0xFE 0xFE 0xE0 radio_addr 0xFA 0xFD  (NG/Error)
 */
class IcomCIVClient(
    private val context: Context,
    private val usbDevice: UsbDevice,
    private val radioAddress: Byte = 0x94.toByte()  // Default IC-7300
) {
    private var usbSerialPort: UsbSerialPort? = null
    private val controllerAddress: Byte = 0xE0.toByte()

    companion object {
        private const val TAG = "IcomCIVClient"

        // CI-V Protocol bytes
        private const val PREAMBLE: Byte = 0xFE.toByte()
        private const val POSTAMBLE: Byte = 0xFD.toByte()
        private const val CMD_READ_FREQUENCY: Byte = 0x03.toByte()
        private const val CMD_SET_FREQUENCY: Byte = 0x05.toByte()
        private const val CMD_PTT: Byte = 0x1C.toByte()
        private const val RESPONSE_OK: Byte = 0xFB.toByte()
        private const val RESPONSE_NG: Byte = 0xFA.toByte()

        // Connection parameters
        private const val BAUD_RATE = 19200
        private const val DATA_BITS = 8
        private const val STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val PARITY = UsbSerialPort.PARITY_NONE
        private const val TIMEOUT_MS = 1000
    }

    @Synchronized
    fun connect(): Boolean {
        disconnect()

        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            // Find the USB serial driver for this device
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            Log.d(TAG, "Found ${availableDrivers.size} USB serial driver(s)")

            val driver = availableDrivers.firstOrNull { it.device.deviceId == usbDevice.deviceId }

            if (driver == null) {
                Log.e(TAG, "No USB serial driver found for device ${usbDevice.deviceName} (VID:${String.format("%04X", usbDevice.vendorId)} PID:${String.format("%04X", usbDevice.productId)})")
                return false
            }

            Log.d(TAG, "Using driver: ${driver.javaClass.simpleName} for device ${usbDevice.deviceName}")

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                Log.e(TAG, "Failed to open USB connection - permission denied?")
                return false
            }

            // Get the correct port
            // IC-705 and similar radios have 2 ports: port 0 = serial/RTTY, port 1 = CI-V
            Log.d(TAG, "Device has ${driver.ports.size} port(s)")

            val port = if (driver.ports.size > 1) {
                // Use port 1 for CI-V on multi-port devices (IC-705, IC-9700, etc.)
                Log.d(TAG, "Multi-port device detected, using port 1 for CI-V")
                driver.ports[1]
            } else {
                // Single port device, use port 0
                driver.ports.firstOrNull()
            }

            if (port == null) {
                Log.e(TAG, "No USB serial port available")
                connection.close()
                return false
            }
            Log.i(TAG, "Using port ${port.portNumber} for CI-V communication")

            // Open and configure the port
            port.open(connection)

            // IC-705 USB port might use different baud rates. Try common ones.
            // Try 115200 first (common for USB), then 19200 (standard CI-V), then 9600
            val baudRatesToTry = listOf(115200, 19200, 9600)
            var successfulBaud = 0

            for (baudRate in baudRatesToTry) {
                try {
                    port.setParameters(baudRate, DATA_BITS, STOP_BITS, PARITY)
                    Log.d(TAG, "Trying ${baudRate} baud...")
                    Thread.sleep(100)

                    // Try to read frequency as test
                    port.purgeHwBuffers(true, true)
                    val testCmd = byteArrayOf(PREAMBLE, PREAMBLE, radioAddress, controllerAddress, CMD_READ_FREQUENCY, POSTAMBLE)
                    port.write(testCmd, 500)
                    Thread.sleep(200)

                    val testBuf = ByteArray(32)
                    val testRead = port.read(testBuf, 300)
                    if (testRead > 3 && testBuf[0] == PREAMBLE && testBuf[1] == PREAMBLE) {
                        Log.i(TAG, "Baud rate ${baudRate} looks good! Got valid response.")
                        successfulBaud = baudRate
                        break
                    } else if (testRead > 0) {
                        Log.d(TAG, "Baud ${baudRate}: got ${testRead} bytes: ${testBuf.take(testRead).joinToString(" ") { "%02X".format(it) }}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Baud ${baudRate} failed: ${e.message}")
                }
            }

            if (successfulBaud == 0) {
                Log.w(TAG, "No working baud rate found, using 19200 as fallback")
                successfulBaud = BAUD_RATE
                port.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY)
            } else {
                port.setParameters(successfulBaud, DATA_BITS, STOP_BITS, PARITY)
            }

            Log.i(TAG, "Port configured: ${successfulBaud} baud, ${DATA_BITS}N${STOP_BITS}")

            // Set DTR and RTS for flow control
            try {
                port.dtr = true
                port.rts = true
                Log.d(TAG, "Set DTR=true, RTS=true")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set DTR/RTS: ${e.message}")
            }

            // Purge buffers
            try {
                port.purgeHwBuffers(true, true)
                Log.d(TAG, "Purged hardware buffers")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to purge buffers: ${e.message}")
            }

            usbSerialPort = port
            Log.i(TAG, "Connected to Icom radio via USB (address: 0x${radioAddress.toString(16)})")

            true

        } catch (e: IOException) {
            Log.e(TAG, "IOException connecting to USB serial: ${e.message}", e)
            disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to USB serial: ${e.message}", e)
            disconnect()
            false
        }
    }

    @Synchronized
    fun disconnect() {
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB serial port: ${e.message}")
        }
        usbSerialPort = null
    }

    /**
     * Test basic communication with radio by sending a read frequency command.
     */
    private fun testCommunication() {
        val port = usbSerialPort ?: return

        try {
            Log.d(TAG, "Testing communication with read frequency command...")

            // Build simple read frequency command: FE FE <addr> E0 03 FD
            val command = byteArrayOf(
                PREAMBLE,
                PREAMBLE,
                radioAddress,
                controllerAddress,
                CMD_READ_FREQUENCY,
                POSTAMBLE
            )

            // Send command
            port.write(command, TIMEOUT_MS)
            Log.d(TAG, "Test command sent (${command.size} bytes): ${command.joinToString(" ") { "%02X".format(it) }}")

            // Read response
            Thread.sleep(200)
            val buffer = ByteArray(256)
            val bytesRead = port.read(buffer, 500)

            if (bytesRead > 0) {
                val response = buffer.copyOfRange(0, bytesRead)
                Log.i(TAG, "Test response (${bytesRead} bytes): ${response.joinToString(" ") { "%02X".format(it) }}")
            } else {
                Log.w(TAG, "No response to test command")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during communication test: ${e.message}", e)
        }
    }

    /**
     * Get the current radio frequency in Hz.
     * Returns null if read fails.
     */
    @Synchronized
    fun getFrequency(): Long? {
        val port = usbSerialPort ?: return null

        try {
            // Flush any old data from the input buffer
            try {
                val flushBuffer = ByteArray(256)
                var flushedBytes = 0
                var bytesRead: Int
                while (port.read(flushBuffer, 10).also { bytesRead = it } > 0) {
                    flushedBytes += bytesRead
                }
                if (flushedBytes > 0) {
                    Log.d(TAG, "Flushed $flushedBytes old bytes from buffer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during buffer flush: ${e.message}")
            }

            // Build CI-V read frequency command: FE FE <addr> E0 03 FD
            val command = byteArrayOf(
                PREAMBLE,
                PREAMBLE,
                radioAddress,
                controllerAddress,
                CMD_READ_FREQUENCY,
                POSTAMBLE
            )

            // Send command
            port.write(command, TIMEOUT_MS)
            Log.d(TAG, "Sent read frequency command (${command.size} bytes): ${command.joinToString(" ") { "%02X".format(it) }}")

            // Wait a bit for radio to process
            Thread.sleep(100)

            // Wait for response (skip echo if present)
            val response = waitForResponse(skipEcho = true, echoLength = command.size)

            if (response == null) {
                Log.w(TAG, "No response to read frequency command")
                return null
            }

            // Response format: FE FE E0 <addr> 03 <freq_bcd> FD
            // Need at least 11 bytes: preamble(2) + addresses(2) + cmd(1) + freq(5) + postamble(1)
            if (response.size < 11) {
                Log.w(TAG, "Response too short: ${response.size} bytes")
                return null
            }

            // Extract BCD frequency bytes (5 bytes after command byte)
            val freqBCD = response.copyOfRange(5, 10)
            val frequencyHz = bcdToFrequency(freqBCD)

            Log.i(TAG, "Current radio frequency: $frequencyHz Hz")
            return frequencyHz

        } catch (e: IOException) {
            Log.e(TAG, "IOException reading frequency: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading frequency: ${e.message}", e)
            return null
        }
    }

    /**
     * Set the radio frequency in Hz.
     */
    @Synchronized
    fun setFrequency(frequencyHz: Long): Boolean {
        val port = usbSerialPort ?: return false

        try {
            // Flush any old data from the input buffer
            try {
                val flushBuffer = ByteArray(256)
                var flushedBytes = 0
                var bytesRead: Int
                while (port.read(flushBuffer, 10).also { bytesRead = it } > 0) {
                    flushedBytes += bytesRead
                }
                if (flushedBytes > 0) {
                    Log.d(TAG, "Flushed $flushedBytes old bytes from buffer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during buffer flush: ${e.message}")
            }

            // Convert frequency to BCD format
            val freqBCD = frequencyToBCD(frequencyHz)

            // Build CI-V command: FE FE <addr> E0 05 <freq_bcd> FD
            val command = byteArrayOf(
                PREAMBLE,
                PREAMBLE,
                radioAddress,
                controllerAddress,
                CMD_SET_FREQUENCY,
                *freqBCD,  // Spread operator to insert BCD bytes
                POSTAMBLE
            )

            // Send command
            port.write(command, TIMEOUT_MS)
            Log.d(TAG, "Sent frequency command (${command.size} bytes): ${command.joinToString(" ") { "%02X".format(it) }}")

            // Wait a bit for radio to process
            Thread.sleep(100)

            // Wait for response (skip echo if present)
            val response = waitForResponse(skipEcho = true, echoLength = command.size)
            val success = response != null && isResponseOK(response)

            if (success) {
                Log.i(TAG, "Frequency set to $frequencyHz Hz")
            } else {
                Log.w(TAG, "Failed to set frequency to $frequencyHz Hz")
            }

            return success

        } catch (e: IOException) {
            Log.e(TAG, "IOException setting frequency: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting frequency: ${e.message}", e)
            return false
        }
    }

    /**
     * Set PTT (Push-To-Talk) on or off.
     */
    @Synchronized
    fun setPtt(enabled: Boolean): Boolean {
        val port = usbSerialPort ?: return false

        try {
            // Flush any old data from the input buffer
            try {
                val flushBuffer = ByteArray(256)
                var flushedBytes = 0
                var bytesRead: Int
                while (port.read(flushBuffer, 10).also { bytesRead = it } > 0) {
                    flushedBytes += bytesRead
                }
                if (flushedBytes > 0) {
                    Log.d(TAG, "Flushed $flushedBytes old bytes from buffer")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during buffer flush: ${e.message}")
            }

            // Build CI-V command: FE FE <addr> E0 1C 00 <01|00> FD
            val command = byteArrayOf(
                PREAMBLE,
                PREAMBLE,
                radioAddress,
                controllerAddress,
                CMD_PTT,
                0x00.toByte(),
                if (enabled) 0x01.toByte() else 0x00.toByte(),
                POSTAMBLE
            )

            // Send command
            port.write(command, TIMEOUT_MS)
            Log.d(TAG, "Sent PTT ${if (enabled) "ON" else "OFF"} command")

            // Wait for response (skip echo if present)
            val response = waitForResponse(skipEcho = true, echoLength = command.size)
            val success = response != null && isResponseOK(response)

            if (success) {
                Log.i(TAG, "PTT ${if (enabled) "enabled" else "disabled"}")
            } else {
                Log.w(TAG, "Failed to set PTT to ${if (enabled) "ON" else "OFF"}")
            }

            return success

        } catch (e: IOException) {
            Log.e(TAG, "IOException setting PTT: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting PTT: ${e.message}", e)
            return false
        }
    }

    /**
     * Convert frequency in Hz to Icom BCD format (5 bytes, LSB first).
     * Example: 14078000 Hz -> 78 80 07 14 00
     */
    private fun frequencyToBCD(freqHz: Long): ByteArray {
        val bcd = ByteArray(5)
        var freq = freqHz

        for (i in 0 until 5) {
            val digit1 = (freq % 10).toInt()  // Ones place (low nibble)
            freq /= 10
            val digit2 = (freq % 10).toInt()  // Tens place (high nibble)
            freq /= 10

            // Pack two digits into one byte (BCD format: high nibble = tens, low nibble = ones)
            bcd[i] = ((digit2 shl 4) or digit1).toByte()
        }

        return bcd
    }

    /**
     * Convert Icom BCD format (5 bytes, LSB first) to frequency in Hz.
     * Example: 78 80 07 14 00 -> 14078000 Hz
     */
    private fun bcdToFrequency(bcd: ByteArray): Long {
        var freq = 0L

        // Process bytes in reverse order (MSB last in array)
        for (i in 4 downTo 0) {
            val byte = bcd[i].toInt() and 0xFF
            val highNibble = (byte shr 4) and 0x0F  // Tens digit
            val lowNibble = byte and 0x0F           // Ones digit

            freq = freq * 10 + highNibble
            freq = freq * 10 + lowNibble
        }

        return freq
    }

    /**
     * Wait for a response from the radio.
     * Returns the response bytes or null on timeout.
     *
     * @param skipEcho If true, skip the command echo before looking for response
     * @param echoLength Expected length of echo to skip
     */
    private fun waitForResponse(skipEcho: Boolean = false, echoLength: Int = 0): ByteArray? {
        val port = usbSerialPort ?: return null
        val buffer = ByteArray(256)
        val allBytes = mutableListOf<Byte>()
        val startTime = System.currentTimeMillis()
        var echoSkipped = !skipEcho  // If not skipping echo, consider it already skipped

        try {
            while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                val numBytesRead = port.read(buffer, 200)

                if (numBytesRead > 0) {
                    // Log all bytes received
                    val bytesReceived = buffer.copyOfRange(0, numBytesRead)
                    Log.d(TAG, "Received ${numBytesRead} bytes: ${bytesReceived.joinToString(" ") { "%02X".format(it) }}")

                    for (i in 0 until numBytesRead) {
                        allBytes.add(buffer[i])
                    }

                    // Skip echo bytes if requested
                    if (!echoSkipped && allBytes.size >= echoLength) {
                        // Check if we have the echo (starts with FE FE and matches expected length)
                        if (allBytes.size >= echoLength &&
                            allBytes[0] == PREAMBLE &&
                            allBytes[1] == PREAMBLE) {
                            // Remove echo bytes
                            Log.d(TAG, "Skipping echo: ${allBytes.take(echoLength).joinToString(" ") { "%02X".format(it) }}")
                            repeat(echoLength) { allBytes.removeAt(0) }
                            echoSkipped = true
                            if (allBytes.isNotEmpty()) {
                                Log.d(TAG, "After echo removal, ${allBytes.size} bytes remain: ${allBytes.joinToString(" ") { "%02X".format(it) }}")
                            }
                        }
                    }

                    // Give radio time to send complete response
                    Thread.sleep(50)

                    // Look for complete messages ending with 0xFD
                    for (i in allBytes.indices) {
                        if (allBytes[i] == POSTAMBLE) {
                            // Found a message terminator, check if it's a valid response
                            val messageStart = maxOf(0, i - 30)  // Look back up to 30 bytes

                            // Look for response pattern starting from various positions
                            for (start in messageStart..i) {
                                if (start + 5 <= allBytes.size &&
                                    allBytes[start] == PREAMBLE &&
                                    start + 1 < allBytes.size &&
                                    allBytes[start + 1] == PREAMBLE) {

                                    // Found FE FE - extract this message
                                    val msg = allBytes.subList(start, i + 1).toByteArray()
                                    Log.d(TAG, "Found message: ${msg.joinToString(" ") { "%02X".format(it) }}")

                                    // Check if it's from the radio (FE FE E0 radioAddr)
                                    if (msg.size >= 6 &&
                                        msg[2] == controllerAddress &&
                                        msg[3] == radioAddress) {
                                        Log.i(TAG, "Valid response from radio")
                                        return msg
                                    }
                                    // It might be an echo, keep looking for actual response
                                }
                            }
                        }
                    }
                }

                Thread.sleep(20)
            }

            Log.w(TAG, "Response timeout - received ${allBytes.size} total bytes: ${allBytes.joinToString(" ") { "%02X".format(it) }}")
            return null

        } catch (e: IOException) {
            Log.e(TAG, "IOException reading response: ${e.message}", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response: ${e.message}", e)
            return null
        }
    }

    /**
     * Check if response indicates success (0xFB).
     */
    private fun isResponseOK(response: ByteArray): Boolean {
        // Look for OK response: FE FE E0 <addr> FB FD
        return response.size >= 6 &&
                response[0] == PREAMBLE &&
                response[1] == PREAMBLE &&
                response[2] == controllerAddress &&
                response[3] == radioAddress &&
                response[4] == RESPONSE_OK &&
                response[5] == POSTAMBLE
    }
}
