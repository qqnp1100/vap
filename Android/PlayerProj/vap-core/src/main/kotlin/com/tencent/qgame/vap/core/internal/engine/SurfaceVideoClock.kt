package com.tencent.qgame.vap.core.internal.engine

/** Presentation clock for a session. Each iteration starts at the earlier of the two track PTSs. */
internal class SurfaceVideoClock(
    isPlaying: Boolean,
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
) {
    private val lock = Object()
    private var playing = isPlaying
    private var pausedAtUs = nowUs()
    private var originPtsUs: Long? = null
    private var anchorUs = 0L
    private var anchorPositionUs = 0L
    private var lastPositionUs = 0L

    fun setPlaying(value: Boolean) = synchronized(lock) {
        if (playing != value) {
            val now = nowUs()
            if (value) anchorUs += now - pausedAtUs else pausedAtUs = now
            playing = value
            lock.notifyAll()
        }
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }

    fun startIteration(videoPtsUs: Long, audioPtsUs: Long?) = synchronized(lock) {
        originPtsUs = minOf(videoPtsUs, audioPtsUs ?: videoPtsUs)
        anchorUs = if (playing) nowUs() else pausedAtUs
        anchorPositionUs = 0
        lastPositionUs = 0
    }

    fun awaitPlaying(cancelled: () -> Boolean): Boolean = synchronized(lock) {
        while (!playing && !cancelled()) lock.wait()
        !cancelled()
    }

    fun awaitPresentation(
        ptsUs: Long,
        cancelled: () -> Boolean,
        audioPositionUs: () -> Long?,
    ) {
        while (awaitPlaying(cancelled)) {
            val audioUs = audioPositionUs()
            synchronized(lock) {
                if (cancelled()) return
                if (!playing) return@synchronized
                val now = nowUs()
                val origin = originPtsUs ?: ptsUs.also {
                    originPtsUs = it
                    anchorUs = now
                }
                val position = if (audioUs != null) {
                    maxOf(lastPositionUs, audioUs - origin).also {
                        anchorPositionUs = it
                        anchorUs = now
                    }
                } else anchorPositionUs + now - anchorUs
                lastPositionUs = position
                val remainingUs = ptsUs - origin - position
                if (remainingUs <= 100) return
                lock.wait(minOf(remainingUs / 1_000, 10).coerceAtLeast(1))
            }
        }
    }

    fun reset() = synchronized(lock) {
        originPtsUs = null
        anchorPositionUs = 0
        lastPositionUs = 0
    }
}
