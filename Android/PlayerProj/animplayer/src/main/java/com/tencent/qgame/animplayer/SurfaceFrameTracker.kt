package com.tencent.qgame.animplayer

/** Associates latched SurfaceTexture images with the decoder output that supplied them. */
internal class SurfaceFrameTracker {
    private val lock = Object()
    private val pending = LinkedHashMap<Long, Int>()
    private var lastSubmitted = Long.MIN_VALUE

    fun submit(nowNs: Long, frameIndex: Int): Long = synchronized(lock) {
        // Media PTS can repeat within a clip and starts over after each loop.
        lastSubmitted = maxOf(nowNs, lastSubmitted + 1)
        if (pending.size == 64) pending.remove(pending.keys.first())
        pending[lastSubmitted] = frameIndex
        lastSubmitted
    }

    fun latch(timestampNs: Long, useTimestamp: Boolean): Int? = synchronized(lock) {
        // Before API 21 the producer timestamp is unspecified. There is only one frame in flight.
        val timestamp = if (useTimestamp) timestampNs else pending.keys.firstOrNull()
        val frameIndex = pending[timestamp] ?: return@synchronized null
        val iterator = pending.keys.iterator()
        while (iterator.hasNext()) {
            val submitted = iterator.next()
            iterator.remove()
            if (submitted == timestamp) break
        }
        lock.notifyAll()
        frameIndex
    }

    fun awaitLatest(cancelled: () -> Boolean): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (pending.containsKey(lastSubmitted)) {
            if (cancelled() || System.nanoTime() >= deadline) return@synchronized false
            lock.wait(10)
        }
        true
    }
}
