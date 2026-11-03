package com.tencent.qgame.playerproj.player

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

class FileUtilTest {
    @get:Rule val folder = TemporaryFolder()

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun replacesPartialCacheOnlyAfterSourceIsVerified() {
        val destination = folder.newFile("demo.mp4").apply { writeText("partial") }
        val complete = "complete asset".toByteArray()
        FileUtil.installVerifiedFile(destination, md5(complete)) { ByteArrayInputStream(complete) }
        assertArrayEquals(complete, destination.readBytes())
        assertEquals(listOf("demo.mp4"), folder.root.list()!!.toList())
    }

    @Test fun failedCopyPreservesExistingFileAndRemovesTemporaryFile() {
        val destination = folder.newFile("demo.mp4").apply { writeText("old asset") }
        try {
            FileUtil.installVerifiedFile(destination, md5("valid asset".toByteArray())) {
                ByteArrayInputStream("damaged".toByteArray())
            }
            fail("A bad checksum must fail")
        } catch (expected: IOException) {
            assertEquals("old asset", destination.readText())
            assertEquals(listOf("demo.mp4"), folder.root.list()!!.toList())
        }
    }

    @Test fun cancellationDuringCopyClosesStreamAndDoesNotPublishFile() {
        val destination = java.io.File(folder.root, "demo.mp4")
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int { Thread.currentThread().interrupt(); return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.currentThread().interrupt()
                buffer[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        }
        try {
            FileUtil.installVerifiedFile(destination, md5(byteArrayOf(1))) { source }
            fail("Cancellation must fail")
        } catch (expected: InterruptedIOException) {
            assertTrue(closed)
            assertFalse(destination.exists())
            assertEquals(0, folder.root.list()!!.size)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun changedFileInvalidatesCachedChecksum() {
        val destination = folder.newFile("demo.mp4").apply { writeText("one") }
        val first = FileUtil.getFileMD5(destination)
        destination.writeText("different length")
        assertNotEquals(first, FileUtil.getFileMD5(destination))
    }
}
