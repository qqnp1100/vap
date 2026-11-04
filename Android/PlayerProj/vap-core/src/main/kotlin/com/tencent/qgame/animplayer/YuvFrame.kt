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
package com.tencent.qgame.animplayer

import java.nio.ByteBuffer

/** Kept under its original JVM name for the legacy YUV renderer's method descriptor. */
class YuvFrame(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= 16L * 1024 * 1024) {
            "YUV frame dimensions exceed the buffer budget: ${width}x${height}"
        }
    }

    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val y: ByteBuffer = ByteBuffer.allocateDirect(width * height)
    val u: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
    val v: ByteBuffer = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
    var index = 0
    var presentationTimeUs = 0L
}

/** A checked-out frame belongs to the decoder or renderer until it is returned. */
internal class YuvFramePool(private val width: Int, private val height: Int) {
    private val available = ArrayList<YuvFrame>(2)
    var allocated = 0
        private set
    var dropped = 0
        private set
    var rendered = 0

    @Synchronized
    fun acquire(): YuvFrame? {
        if (available.isNotEmpty()) return available.removeAt(available.lastIndex)
        if (allocated == 2) {
            dropped++
            return null
        }
        return YuvFrame(width, height).also { allocated++ }
    }

    @Synchronized
    fun recycle(frame: YuvFrame) {
        available.add(frame)
    }
}

/** Copies only visible samples, leaving the codec-owned buffer's range intact. */
internal object YuvPlaneCopy {
    fun copyPacked(
        source: ByteBuffer,
        offset: Int,
        size: Int,
        stride: Int,
        sliceHeight: Int,
        left: Int,
        top: Int,
        semiplanar: Boolean,
        frame: YuvFrame
    ) {
        val start = offset.toLong()
        val end = start + size
        require(offset >= 0 && size > 0 && end <= source.capacity()) { "Invalid YUV output range" }
        require(stride > 0 && sliceHeight > 0 && left >= 0 && top >= 0 && left % 2 == 0 && top % 2 == 0 &&
            left.toLong() + frame.width <= stride && top.toLong() + frame.height <= sliceHeight) {
            "YUV crop exceeds its padded image or is not chroma-aligned"
        }

        val lumaEnd = start + stride.toLong() * sliceHeight
        val chromaStride = if (semiplanar) stride else stride / 2 + stride % 2
        val chromaRows = sliceHeight / 2 + sliceHeight % 2
        val uEnd = if (semiplanar) end else lumaEnd + chromaStride.toLong() * chromaRows
        require(lumaEnd <= end && uEnd <= end) { "Truncated padded YUV output" }
        copy(source, start, lumaEnd, stride, 1, left, top, frame.width, frame.height, frame.y)
        val pixelStride = if (semiplanar) 2 else 1
        copy(source, lumaEnd, uEnd, chromaStride, pixelStride, left / 2, top / 2,
            frame.chromaWidth, frame.chromaHeight, frame.u)
        val vStart = if (semiplanar) lumaEnd + 1 else uEnd
        // For NV12, V starts one byte after U but has the same row origin.
        require(!semiplanar || (left / 2L + frame.chromaWidth - 1) * 2 + 1 < chromaStride) {
            "Truncated interleaved chroma row"
        }
        copy(source, vStart, end, chromaStride, pixelStride, left / 2, top / 2,
            frame.chromaWidth, frame.chromaHeight, frame.v)
    }

    fun copy(
        source: ByteBuffer,
        start: Long,
        end: Long,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        target: ByteBuffer
    ) {
        require(start >= 0 && start <= end && end <= source.capacity()) { "Invalid YUV plane range" }
        require(rowStride > 0 && pixelStride > 0 && width > 0 && height > 0 && left >= 0 && top >= 0) {
            "Invalid YUV plane dimensions or stride"
        }
        val lastColumn = (left.toLong() + width - 1) * pixelStride
        require(lastColumn < rowStride && width.toLong() * height <= target.capacity()) {
            "YUV plane crop exceeds its row or target"
        }
        val lastSample = start + (top.toLong() + height - 1) * rowStride + lastColumn
        require(lastSample < end) { "Truncated YUV plane" }

        target.clear()
        val originalLimit = source.limit()
        val originalPosition = source.position()
        try {
            source.limit(end.toInt())
            var rowStart = (start + top.toLong() * rowStride + left.toLong() * pixelStride).toInt()
            repeat(height) {
                if (pixelStride == 1) {
                    source.limit(rowStart + width)
                    source.position(rowStart)
                    target.put(source)
                } else {
                    var sample = rowStart
                    repeat(width) {
                        target.put(source.get(sample))
                        sample += pixelStride
                    }
                }
                rowStart += rowStride
            }
            target.flip()
        } finally {
            source.limit(originalLimit)
            source.position(originalPosition)
        }
    }
}
