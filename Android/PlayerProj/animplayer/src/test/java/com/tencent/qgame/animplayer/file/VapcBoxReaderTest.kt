package com.tencent.qgame.animplayer.file

import android.media.MediaExtractor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files

class VapcBoxReaderTest {
    @Test fun shortReadsUseLegacySkipWithoutReadingBoxPayload() {
        val bytes = box("free", ByteArray(31)) + box("vapc", "{config}".toByteArray())
        val source = TestContainer(bytes, chunkSize = 1)
        assertArrayEquals("{config}".toByteArray(), VapcBoxReader.read(source))
        assertEquals(16 + "{config}".length, source.bytesRead)
        assertEquals(1, source.closeCount)
    }

    @Test fun readsExtendedSizeBox() {
        val source = TestContainer(box("free", ByteArray(3), extended = true) + box("vapc", byteArrayOf(42), extended = true))
        assertArrayEquals(byteArrayOf(42), VapcBoxReader.read(source))
    }

    @Test fun sizeZeroBoxStopsScanningPayload() {
        val source = TestContainer(header(0, "mdat") + box("vapc", byteArrayOf(42)))
        assertNull(VapcBoxReader.read(source))
        assertEquals(8, source.bytesRead)
        assertEquals(1, source.closeCount)
    }

    @Test fun sizeZeroConfigHasBoundedEofPayload() {
        assertArrayEquals(byteArrayOf(1, 2, 3), VapcBoxReader.read(TestContainer(header(0, "vapc") + byteArrayOf(1, 2, 3))))
    }

    @Test fun truncatedHeaderAndPayloadFailAndClose() {
        for (bytes in listOf(byteArrayOf(0, 0, 0), header(12, "vapc") + byteArrayOf(1))) {
            val source = TestContainer(bytes)
            assertThrows(IOException::class.java) { VapcBoxReader.read(source) }
            assertEquals(1, source.closeCount)
        }
    }

    @Test fun invalidAndOversizedLengthsFailBeforePayloadRead() {
        for (length in listOf(2L, 0xffffffffL, VapcBoxReader.MAX_CONFIG_BYTES.toLong() + 9)) {
            val source = TestContainer(header(length, "vapc"))
            assertThrows(IOException::class.java) { VapcBoxReader.read(source) }
            assertEquals(8, source.bytesRead)
            assertEquals(1, source.closeCount)
        }
    }

    @Test fun knownFileLengthRejectsBoxesPastEnd() {
        val bytes = header(4096, "free")
        val source = TestContainer(bytes, knownLength = bytes.size.toLong())
        assertThrows(IOException::class.java) { VapcBoxReader.read(source) }
        assertEquals(8, source.bytesRead)
    }

    @Test fun zeroProgressReadFailsWithoutSpinning() {
        val source = TestContainer(box("vapc", byteArrayOf(1)), chunkSize = 0)
        assertThrows(IOException::class.java) { VapcBoxReader.read(source) }
        assertEquals(1, source.closeCount)
    }

    @Test fun missingConfigAndParseFailureCanReopenFile() {
        val file = Files.createTempFile("vap-box", ".mp4").toFile()
        try {
            file.writeBytes(box("free", ByteArray(3)))
            val source = FileContainer(file)
            repeat(3) { assertNull(VapcBoxReader.read(source)); source.close() }
            file.writeBytes(box("vapc", byteArrayOf(42)))
            repeat(3) { assertArrayEquals(byteArrayOf(42), VapcBoxReader.read(source)); source.close() }
        } finally {
            file.delete()
        }
    }

    @Test fun streamContainerRestartsAfterClose() {
        val source = StreamContainer(box("vapc", byteArrayOf(42)))
        repeat(3) {
            assertArrayEquals(byteArrayOf(42), VapcBoxReader.read(source))
            source.close()
        }
    }

    private fun box(type: String, payload: ByteArray, extended: Boolean = false): ByteArray =
        if (extended) header(1, type) + longBytes(payload.size.toLong() + 16) + payload
        else header(payload.size.toLong() + 8, type) + payload

    private fun header(length: Long, type: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeInt(length.toInt()); it.writeBytes(type) }
        return bytes.toByteArray()
    }

    private fun longBytes(value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeLong(value) }
        return bytes.toByteArray()
    }

    private class TestContainer(
        private val bytes: ByteArray,
        private val chunkSize: Int = Int.MAX_VALUE,
        private val knownLength: Long = -1L
    ) : IFileContainer {
        private var input = ByteArrayInputStream(bytes)
        var bytesRead = 0
        var closeCount = 0
        override fun setDataSource(extractor: MediaExtractor) = Unit
        override fun startRandomRead() { input = ByteArrayInputStream(bytes) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, minOf(len, chunkSize)).also {
            if (it > 0) bytesRead += it
        }
        override fun skip(pos: Long) {
            if (input.skip(pos) != pos) throw java.io.EOFException("Truncated box")
        }
        override fun getLength(): Long = knownLength
        override fun closeRandomRead() { closeCount++; input.close() }
        override fun close() = closeRandomRead()
    }
}
