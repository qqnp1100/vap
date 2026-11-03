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

import android.media.MediaExtractor

interface IFileContainer {

    fun setDataSource(extractor: MediaExtractor)

    fun startRandomRead()

    fun read(b: ByteArray, off: Int, len: Int): Int

    fun skip(pos: Long)

    /** Total source bytes, or -1 when unknown. The source must remain immutable during playback. */
    fun getLength(): Long = -1L

    /**
     * Advance by [length] bytes. Built-in sources verify the advance or throw.
     * The default delegates to legacy [skip] so existing seekable sources can still skip efficiently.
     * Override this if [skip] can return without advancing by the requested number of bytes.
     */
    fun skipFully(length: Long) {
        require(length >= 0)
        if (length > 0L) skip(length)
    }

    fun closeRandomRead()

    fun close()

}
