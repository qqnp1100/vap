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
package com.tencent.qgame.animplayer.file

import android.annotation.TargetApi
import android.media.MediaExtractor
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.EOFException

@TargetApi(Build.VERSION_CODES.M)
class StreamContainer(private val bytes: ByteArray) : IFileContainer {

    private var stream: ByteArrayInputStream? = null

    override fun setDataSource(extractor: MediaExtractor) {
        val dataSource = StreamMediaDataSource(bytes)
        extractor.setDataSource(dataSource)
    }

    override fun startRandomRead() {
        stream = ByteArrayInputStream(bytes)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return stream?.read(b, off, len) ?: -1
    }

    override fun skip(pos: Long) {
        skipFully(pos)
    }

    override fun getLength(): Long = bytes.size.toLong()

    override fun skipFully(length: Long) {
        require(length >= 0)
        val input = stream ?: throw IllegalStateException("Random read is not open")
        if (input.skip(length) != length) throw EOFException("Truncated stream while skipping")
    }

    override fun closeRandomRead() {
        stream?.close()
        stream = null
    }

    override fun close() {
        closeRandomRead()
    }
}
