package com.js8call.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class SntpClient {
    data class Result(val offsetMs: Long, val roundTripMs: Long)

    fun requestTime(server: String, timeoutMs: Int): Result? {
        val address = InetAddress.getByName(server)
        val buffer = ByteArray(NTP_PACKET_SIZE)
        buffer[0] = NTP_MODE_CLIENT

        val requestTime = System.currentTimeMillis()
        writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)

        val socket = DatagramSocket()
        socket.soTimeout = timeoutMs
        return try {
            val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            socket.send(request)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val responseTime = System.currentTimeMillis()

            val originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET)
            if (originateTime == 0L || transmitTime == 0L) return null

            val roundTrip = responseTime - requestTime - (transmitTime - receiveTime)
            val clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2
            Result(clockOffset, roundTrip)
        } catch (e: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0..3) {
            seconds = (seconds shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        for (i in 4..7) {
            fraction = (fraction shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        if (seconds == 0L && fraction == 0L) {
            return 0L
        }
        val millis = (seconds - NTP_EPOCH_OFFSET) * 1000L +
            (fraction * 1000L) / FRACTION_SCALE
        return millis
    }

    private fun writeTimeStamp(buffer: ByteArray, offset: Int, timeMs: Long) {
        val seconds = (timeMs / 1000L) + NTP_EPOCH_OFFSET
        val fraction = (timeMs % 1000L) * FRACTION_SCALE / 1000L
        writeUint32(buffer, offset, seconds)
        writeUint32(buffer, offset + 4, fraction)
    }

    private fun writeUint32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    companion object {
        private const val NTP_PORT = 123
        private const val NTP_PACKET_SIZE = 48
        private const val NTP_MODE_CLIENT = 0x1B.toByte()
        private const val NTP_EPOCH_OFFSET = 2208988800L
        private const val FRACTION_SCALE = 0x100000000L
        private const val ORIGINATE_TIME_OFFSET = 24
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40
    }
}
