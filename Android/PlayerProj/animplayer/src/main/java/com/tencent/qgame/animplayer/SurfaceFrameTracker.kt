package com.tencent.qgame.animplayer

import com.tencent.qgame.vap.core.internal.engine.SurfaceFrameTracker as CoreSurfaceFrameTracker

internal class SurfaceFrameTracker {
    private val delegate = CoreSurfaceFrameTracker()

    fun submit(nowNs: Long, frameIndex: Int): Long = delegate.submit(nowNs, frameIndex)

    fun latch(timestampNs: Long, useTimestamp: Boolean): Int? = delegate.latch(timestampNs, useTimestamp)

    fun awaitLatest(cancelled: () -> Boolean): Boolean = delegate.awaitLatest(cancelled)
}
