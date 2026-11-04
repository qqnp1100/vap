package com.tencent.qgame.vap.core.internal.engine

import com.tencent.qgame.vap.core.internal.codec.SurfaceAudioControls
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceVideoClockTest {
    @Test(timeout = 5_000)
    fun delayedAudioWaitsForItsPtsAndDoesNotAdvanceTheVideoClock() {
        val now = AtomicLong()
        val clock = SurfaceVideoClock(true, now::get)
        val audio = SurfaceAudioControls({ throw AssertionError(it) }, false, clock)
        clock.startIteration(videoPtsUs = 0, audioPtsUs = 2_000_000)
        val pcm = FutureTask { audio.beginPcm(2_000_000) { true } }
        val worker = Thread(pcm).apply { start() }
        try {
            // Playback at 500 ms is due, but the audio PCM at 2 s is still held.
            now.set(500_000)
            clock.awaitPresentation(500_000, { false }) { audio.positionUs }
            assertThrows(TimeoutException::class.java) { pcm.get(100, MILLISECONDS) }
            assertNull(audio.positionUs)

            clock.setPlaying(false)
            now.set(4_000_000)
            clock.setPlaying(true)
            assertThrows(TimeoutException::class.java) { pcm.get(100, MILLISECONDS) }
            now.set(5_500_000)
            clock.wake()
            assertTrue(pcm.get(2, SECONDS))
        } finally {
            worker.interrupt()
        }
    }

    @Test(timeout = 5_000)
    fun videoWithLaterPtsWaitsOnTheAudioTimeline() {
        val now = AtomicLong()
        val audioPts = AtomicLong()
        val clock = SurfaceVideoClock(true, now::get)
        clock.startIteration(videoPtsUs = 1_000_000, audioPtsUs = 0)
        val frame = FutureTask {
            clock.awaitPresentation(1_000_000, { false }, audioPts::get)
        }
        val worker = Thread(frame).apply { start() }
        try {
            assertThrows(TimeoutException::class.java) { frame.get(100, MILLISECONDS) }
            audioPts.set(1_000_000)
            clock.wake()
            frame.get(2, SECONDS)
        } finally {
            worker.interrupt()
        }
    }
}
