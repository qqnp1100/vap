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

import android.media.MediaExtractor
import com.tencent.qgame.vap.core.spi.VapMediaInput
import java.io.File
import java.io.FileNotFoundException
import java.io.EOFException
import java.io.RandomAccessFile

internal class FileInput(private val file: File) : VapMediaInput {

    private var randomAccessFile: RandomAccessFile? = null

    init {
        if (!(file.exists() && file.isFile && file.canRead())) throw FileNotFoundException("Unable to read $file")
    }

    override fun setDataSource(extractor: MediaExtractor) {
        extractor.setDataSource(file.toString())
    }

    override fun startRandomRead() {
        closeRandomRead()
        randomAccessFile = RandomAccessFile(file, "r")
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        return randomAccessFile?.read(bytes, offset, length) ?: -1
    }

    override fun getLength(): Long = file.length()

    override fun skipFully(length: Long) {
        require(length >= 0)
        val input = randomAccessFile ?: throw IllegalStateException("Random read is not open")
        val current = input.filePointer
        if (length > input.length() - current) throw EOFException("Truncated file while skipping")
        input.seek(current + length)
    }

    override fun closeRandomRead() {
        val input = randomAccessFile
        randomAccessFile = null
        input?.close()
    }

    override fun close() {
        closeRandomRead()
    }
}
