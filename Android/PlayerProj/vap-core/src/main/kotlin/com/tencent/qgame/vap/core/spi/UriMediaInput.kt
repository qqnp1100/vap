package com.tencent.qgame.vap.core.spi

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException

class SourceNotSeekableException(uri: Uri, cause: Throwable) :
    IOException("The VAP source does not support random access: $uri", cause)

internal class UriSourceAccessException(uri: Uri, cause: Exception) :
    IOException("Unable to open VAP source: $uri", cause)

/** Reopens each content descriptor independently and preserves its offset and length. */
@TargetApi(23)
fun openUriInput(resolver: ContentResolver, uri: Uri): VapMediaInput {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "A local content:// URI is required" }
    return UriMediaInput(resolver, uri)
}

@TargetApi(23)
private class UriMediaInput(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : VapMediaInput {
    private var input: AssetFileDescriptor? = null
    private var position = 0L
    private var size = 0L

    override fun setDataSource(extractor: MediaExtractor) {
        open().use { descriptor ->
            val length = descriptorLength(descriptor)
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, length)
        }
    }

    override fun startRandomRead() {
        closeRandomRead()
        val descriptor = open()
        try {
            size = descriptorLength(descriptor)
            position = 0L
            input = descriptor
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    override fun getLength() = size

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val descriptor = checkNotNull(input)
        if (length == 0) return 0
        val available = (size - position).coerceAtMost(length.toLong()).toInt()
        if (available == 0) return -1
        val count = Os.pread(descriptor.fileDescriptor, bytes, offset, available, descriptor.startOffset + position)
        position += count
        return if (count == 0) -1 else count
    }

    override fun skipFully(length: Long) {
        checkNotNull(input)
        require(length >= 0)
        if (length > size - position) throw EOFException("Truncated content descriptor")
        position += length
    }

    override fun closeRandomRead() {
        val descriptor = input
        input = null
        descriptor?.close()
    }

    override fun close() = closeRandomRead()

    private fun open(): AssetFileDescriptor = try {
        resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException("No descriptor for $uri")
    } catch (error: Exception) {
        throw UriSourceAccessException(uri, error)
    }

    private fun descriptorLength(descriptor: AssetFileDescriptor): Long {
        val fd = descriptor.fileDescriptor
        val end = try {
            val current = Os.lseek(fd, 0, OsConstants.SEEK_CUR)
            try {
                Os.lseek(fd, 0, OsConstants.SEEK_END)
            } finally {
                Os.lseek(fd, current, OsConstants.SEEK_SET)
            }
        } catch (error: Exception) {
            throw SourceNotSeekableException(uri, error)
        }
        val offset = descriptor.startOffset
        val length = descriptor.declaredLength.takeIf { it >= 0 } ?: (end - offset)
        if (offset < 0 || offset > end || length < 0 || length > end - offset) {
            throw EOFException("Invalid content descriptor range")
        }
        return length
    }
}
