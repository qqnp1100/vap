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
package com.tencent.qgame.vap.core.internal.source

import android.content.res.AssetManager
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import com.tencent.qgame.vap.core.spi.VapMediaInput
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal class AssetInput(private val assetManager: AssetManager, private val assetsPath: String): VapMediaInput {

    private var assetsInputStream: InputStream? = null
    private val sourceLength = withAssetDescriptor { it.declaredLength }

    override fun setDataSource(extractor: MediaExtractor) {
        withAssetDescriptor { assetFd ->
            if (assetFd.declaredLength < 0) {
                extractor.setDataSource(assetFd.fileDescriptor)
            } else {
                extractor.setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.declaredLength)
            }
        }
    }

    private inline fun <T> withAssetDescriptor(read: (AssetFileDescriptor) -> T): T {
        val descriptor = assetManager.openFd(assetsPath)
        // The close method exists on API 16, but Closeable was added to the class in API 19.
        try {
            return read(descriptor)
        } finally {
            descriptor.close()
        }
    }

    override fun startRandomRead() {
        closeRandomRead()
        assetsInputStream = assetManager.open(assetsPath, AssetManager.ACCESS_STREAMING)
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        return assetsInputStream?.read(bytes, offset, length) ?: -1
    }

    override fun getLength(): Long = sourceLength

    override fun skipFully(length: Long) {
        require(length >= 0)
        val input = assetsInputStream ?: throw IllegalStateException("Random read is not open")
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped < 0 || skipped > remaining) throw IOException("Invalid asset skip result")
            if (skipped == 0L) {
                if (input.read() < 0) throw EOFException("Truncated asset while skipping")
                remaining--
            } else remaining -= skipped
        }
    }

    override fun closeRandomRead() {
        val input = assetsInputStream
        assetsInputStream = null
        input?.close()
    }

    override fun close() {
        closeRandomRead()
    }
}
