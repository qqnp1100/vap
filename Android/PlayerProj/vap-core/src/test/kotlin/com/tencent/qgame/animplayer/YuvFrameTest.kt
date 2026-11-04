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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class YuvFrameTest {
    @Test
    fun planarCopyRespectsOffsetPaddedRowsAndCrop() {
        val source = ByteBuffer.wrap(ByteArray(90) { it.toByte() })
        source.limit(70)
        source.position(3)
        val frame = YuvFrame(4, 2)

        YuvPlaneCopy.copyPacked(source, 3, 67, 8, 6, 2, 2, false, frame)

        assertArrayEquals(byteArrayOf(21, 22, 23, 24, 29, 30, 31, 32), bytes(frame.y))
        assertArrayEquals(byteArrayOf(56, 57), bytes(frame.u))
        assertArrayEquals(byteArrayOf(68, 69), bytes(frame.v))
        assertEquals(3, source.position())
        assertEquals(70, source.limit())
    }

    @Test
    fun semiplanarCopySeparatesChromaWithoutReadingPaddingBeyondRange() {
        val source = ByteBuffer.wrap(ByteArray(90) { it.toByte() })
        val frame = YuvFrame(4, 2)

        YuvPlaneCopy.copyPacked(source, 5, 62, 8, 6, 2, 2, true, frame)

        assertArrayEquals(byteArrayOf(23, 24, 25, 26, 31, 32, 33, 34), bytes(frame.y))
        assertArrayEquals(byteArrayOf(63, 65), bytes(frame.u))
        assertArrayEquals(byteArrayOf(64, 66), bytes(frame.v))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedChromaRejectedEvenWithBackingCapacity() {
        val source = ByteBuffer.allocate(90)
        YuvPlaneCopy.copyPacked(source, 5, 61, 8, 6, 2, 2, true, YuvFrame(4, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oddCropOriginRejectedForPackedChroma() {
        YuvPlaneCopy.copyPacked(ByteBuffer.allocate(90), 0, 90, 8, 6, 1, 2, false, YuvFrame(4, 2))
    }

    @Test
    fun poolDropsWhenBothSlotsAreOwnedAndReusesReturnedFrame() {
        val pool = YuvFramePool(4, 2)
        val first = checkNotNull(pool.acquire())
        val second = checkNotNull(pool.acquire())
        assertNull(pool.acquire())
        assertEquals(1, pool.dropped)
        pool.recycle(first)
        assertSame(first, pool.acquire())
        assertNull(pool.acquire())
        pool.recycle(second)
        assertSame(second, pool.acquire())
        assertEquals(2, pool.allocated)
    }

    private fun bytes(buffer: ByteBuffer): ByteArray =
        ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
}
