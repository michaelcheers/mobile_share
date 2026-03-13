package com.mobileshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads JPEG frames from an InputStream.
 * Wire protocol: [4 bytes: frame length (little-endian uint32)][N bytes: JPEG data]
 *
 * Call run() to block and read frames until the stream ends.
 * Call shutdown() from another thread to stop.
 */
class FrameReader(
    private val inputStream: InputStream,
    private val onFrame: (Bitmap) -> Unit,
) {

    @Volatile
    private var running = true

    fun shutdown() {
        running = false
        try { inputStream.close() } catch (_: Exception) {}
    }

    fun run() {
        val lengthBytes = ByteArray(4)

        while (running) {
            try {
                readFully(lengthBytes, 0, 4)
                val frameLength = ByteBuffer.wrap(lengthBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt()

                if (frameLength <= 0 || frameLength > 10 * 1024 * 1024) {
                    continue
                }

                val frameData = ByteArray(frameLength)
                readFully(frameData, 0, frameLength)

                val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameLength)
                if (bitmap != null) {
                    onFrame(bitmap)
                }
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var bytesRead = 0
        while (bytesRead < length && running) {
            val count = inputStream.read(buffer, offset + bytesRead, length - bytesRead)
            if (count < 0) throw IOException("End of stream")
            bytesRead += count
        }
    }
}
