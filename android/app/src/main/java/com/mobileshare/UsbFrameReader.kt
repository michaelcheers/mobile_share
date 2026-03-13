package com.mobileshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Background thread that reads JPEG frames from the USB accessory input stream.
 *
 * Wire protocol: [4 bytes: frame length (little-endian uint32)][N bytes: JPEG data]
 */
class UsbFrameReader(
    private val inputStream: FileInputStream,
    private val onFrame: (Bitmap) -> Unit,
) : Thread("UsbFrameReader") {

    @Volatile
    private var running = true

    private val readBuffer = ByteArray(16 * 1024) // 16 KB read buffer

    init {
        isDaemon = true
    }

    fun shutdown() {
        running = false
        interrupt()
    }

    override fun run() {
        val lengthBytes = ByteArray(4)

        while (running) {
            try {
                // Read 4-byte frame length prefix
                readFully(lengthBytes, 0, 4)
                val frameLength = ByteBuffer.wrap(lengthBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt()

                if (frameLength <= 0 || frameLength > 10 * 1024 * 1024) {
                    // Invalid frame length (>10MB) — skip and try to resync
                    continue
                }

                // Read the full JPEG frame
                val frameData = ByteArray(frameLength)
                readFully(frameData, 0, frameLength)

                // Decode JPEG to Bitmap
                val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameLength)
                if (bitmap != null) {
                    onFrame(bitmap)
                }

            } catch (_: IOException) {
                // Stream closed or USB disconnected
                break
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * Read exactly [length] bytes into [buffer] starting at [offset].
     * Blocks until all bytes are read or an exception occurs.
     */
    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var bytesRead = 0
        while (bytesRead < length && running) {
            val count = inputStream.read(buffer, offset + bytesRead, length - bytesRead)
            if (count < 0) throw IOException("End of stream")
            bytesRead += count
        }
    }
}
