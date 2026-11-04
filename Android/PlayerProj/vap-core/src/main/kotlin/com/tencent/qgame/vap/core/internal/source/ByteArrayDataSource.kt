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

import android.annotation.TargetApi
import android.media.MediaDataSource
import android.os.Build

@TargetApi(Build.VERSION_CODES.M)
internal class ByteArrayDataSource(private val bytes: ByteArray) : MediaDataSource() {

    override fun close() {
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size && size <= buffer.size - offset)
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size.toLong() - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long {
        return bytes.size.toLong()
    }
}
