package com.tencent.qgame.animplayer.file

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files

class VapcBoxReaderTest {
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

    private fun box(type: String, payload: ByteArray): ByteArray =
        header(payload.size.toLong() + 8, type) + payload

    private fun header(length: Long, type: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeInt(length.toInt()); it.writeBytes(type) }
        return bytes.toByteArray()
    }
}
