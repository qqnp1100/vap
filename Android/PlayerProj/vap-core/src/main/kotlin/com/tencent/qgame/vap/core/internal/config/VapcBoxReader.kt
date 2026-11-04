package com.tencent.qgame.vap.core.internal.config

import com.tencent.qgame.vap.core.spi.VapConfigInput

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** Reads only top-level MP4 boxes, with a fixed budget before allocating configuration bytes. */
internal object VapcBoxReader {
    const val MAX_CONFIG_BYTES = 8 * 1024 * 1024
    private const val MAX_BOX_COUNT = 10000

    fun read(container: VapConfigInput): ByteArray? {
        try {
            container.startRandomRead()
            val fileLength = container.getLength()
            val header = ByteArray(8)
            var position = 0L
            repeat(MAX_BOX_COUNT) {
                if (!readFully(container, header, allowEof = true)) return null
                val boxStart = position
                position += 8
                var boxLength = unsignedInt(header, 0)
                var headerLength = 8
                val isConfig = header[4] == 'v'.code.toByte() && header[5] == 'a'.code.toByte() &&
                    header[6] == 'p'.code.toByte() && header[7] == 'c'.code.toByte()
                if (boxLength == 1L) {
                    readFully(container, header)
                    if (header[0].toInt() < 0) throw IOException("MP4 box exceeds signed 64-bit range")
                    boxLength = (unsignedInt(header, 0) shl 32) or unsignedInt(header, 4)
                    headerLength = 16
                    position += 8
                }
                if (boxLength == 0L) {
                    // A size-zero box occupies the rest of the file. Never scan its payload as boxes.
                    if (!isConfig) return null
                    if (fileLength >= 0) boxLength = fileLength - boxStart
                    else return readToEnd(container)
                }
                if (boxLength < headerLength || boxLength > Long.MAX_VALUE - boxStart) {
                    throw IOException("Invalid MP4 box size: $boxLength")
                }
                val boxEnd = boxStart + boxLength
                if (fileLength >= 0 && boxEnd > fileLength) throw EOFException("Truncated MP4 box")
                val payloadLength = boxLength - headerLength
                if (isConfig) {
                    if (payloadLength <= 0 || payloadLength > MAX_CONFIG_BYTES) {
                        throw IOException("vapc payload exceeds configuration budget: $payloadLength")
                    }
                    return ByteArray(payloadLength.toInt()).also { readFully(container, it) }
                }
                container.skipFully(payloadLength)
                position = boxEnd
            }
            throw IOException("Too many MP4 boxes")
        } finally {
            container.closeRandomRead()
        }
    }

    private fun readToEnd(container: VapConfigInput): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = container.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) throw IOException("Input made no progress")
            if (output.size().toLong() + count > MAX_CONFIG_BYTES) throw IOException("vapc payload exceeds configuration budget")
            output.write(buffer, 0, count)
        }
        if (output.size() == 0) throw IOException("Empty vapc payload")
        return output.toByteArray()
    }

    private fun readFully(container: VapConfigInput, bytes: ByteArray, allowEof: Boolean = false): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val count = container.read(bytes, offset, bytes.size - offset)
            if (count < 0) {
                if (allowEof && offset == 0) return false
                throw EOFException("Truncated MP4 box")
            }
            if (count == 0 || count > bytes.size - offset) throw IOException("Invalid read result: $count")
            offset += count
        }
        return true
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in offset until offset + 4) value = (value shl 8) or (bytes[index].toLong() and 0xff)
        return value
    }
}
