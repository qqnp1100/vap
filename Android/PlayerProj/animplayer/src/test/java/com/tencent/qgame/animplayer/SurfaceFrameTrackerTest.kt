package com.tencent.qgame.animplayer

import org.junit.Assert.*
import org.junit.Test

class SurfaceFrameTrackerTest {
    @Test
    fun aSkippedImageCannotShiftFusionToAnotherFrame() {
        val frames = SurfaceFrameTracker()
        val skipped = frames.submit(10, 3)
        val displayed = frames.submit(20, 4)
        val next = frames.submit(30, 5)

        assertNull(frames.latch(15, true))
        assertEquals(4, frames.latch(displayed, true))
        assertNull(frames.latch(skipped, true))
        assertNull(frames.latch(displayed, true))
        assertEquals(5, frames.latch(next, true))
        assertTrue(frames.awaitLatest { false })
    }

    @Test
    fun timestampsRemainDistinctAcrossLoopAndClockTies() {
        val frames = SurfaceFrameTracker()
        val previousLoop = frames.submit(100, 79)
        val newLoop = frames.submit(100, 0)

        assertTrue(newLoop > previousLoop)
        assertEquals(79, frames.latch(previousLoop, true))
        assertEquals(0, frames.latch(newLoop, true))
    }

    @Test
    fun pendingFramesAreBoundedDuringAStall() {
        val frames = SurfaceFrameTracker()
        val first = frames.submit(1, 0)
        repeat(64) { frames.submit(it.toLong() + 2, it + 1) }

        assertNull(frames.latch(first, true))
        assertFalse(frames.awaitLatest { true })
        assertEquals(64, frames.latch(65, true))
    }

    @Test
    fun legacySurfaceCanReuseAnUnspecifiedTimestampWithOneFrameInFlight() {
        val frames = SurfaceFrameTracker()
        frames.submit(1, 9)
        assertEquals(9, frames.latch(0, false))
        assertTrue(frames.awaitLatest { false })
        assertNull(frames.latch(0, false))
        frames.submit(2, 0)
        assertEquals(0, frames.latch(0, false))
    }
}
