/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.qgame.playerproj.player

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object FileUtil {
    // Serialise preparation of shared demo assets and put an upper bound on queued I/O.
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(16)) { task -> Thread(task, "demo_file_io") }.apply {
        allowCoreThreadTimeOut(true)
    }
    private data class VerifiedFile(val length: Long, val modified: Long, val md5: String)
    private val verifiedFiles = object : LinkedHashMap<String, VerifiedFile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VerifiedFile>?): Boolean = size > 16
    }

    fun prepareAsset(context: Context, directory: File, name: String, md5: String,
                     ready: (File) -> Unit, failed: (Exception) -> Unit): Future<*> {
        val assets = context.applicationContext.assets
        executor.purge()
        return executor.submit {
            try {
                val file = installVerifiedFile(File(directory, name), md5) { assets.open(name) }
                checkCancelled()
                ready(file)
            } catch (e: InterruptedIOException) {
                // The page has left or a newer play request superseded this request.
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) failed(e)
            }
        }
    }

    internal fun installVerifiedFile(destination: File, expectedMd5: String,
                                     source: () -> InputStream): File {
        checkCancelled()
        if (getFileMD5(destination) == expectedMd5) return destination
        val directory = destination.parentFile ?: throw IOException("Missing asset directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val temporary = File.createTempFile(destination.name, ".part", directory)
        try {
            val digest = MessageDigest.getInstance("MD5")
            source().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            checkCancelled()
            if (toHex(digest.digest()) != expectedMd5) throw IOException("Asset checksum mismatch: ${destination.name}")
            // Both files are in the same directory, so readers see only the complete replacement.
            if (!temporary.renameTo(destination)) throw IOException("Cannot install ${destination.name}")
            remember(destination, expectedMd5)
            return destination
        } finally {
            temporary.delete()
        }
    }

    fun getFileMD5(file: File): String? {
        checkCancelled()
        if (!file.isFile || file.length() <= 0) return null
        val key = file.absolutePath
        val length = file.length()
        val modified = file.lastModified()
        synchronized(verifiedFiles) {
            verifiedFiles[key]?.let { if (it.length == length && it.modified == modified) return it.md5 }
        }
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val result = toHex(digest.digest())
        // Do not cache a checksum if a writer changed the file during verification.
        if (file.length() == length && file.lastModified() == modified) remember(file, result)
        return result
    }

    private fun remember(file: File, md5: String) {
        synchronized(verifiedFiles) {
            verifiedFiles[file.absolutePath] = VerifiedFile(file.length(), file.lastModified(), md5)
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Asset preparation cancelled")
    }

    private fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 15])
            }
        }
    }
}
