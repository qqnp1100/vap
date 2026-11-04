package com.tencent.qgame.vap.core.internal.config

import com.tencent.qgame.vap.core.spi.VideoRect
import com.tencent.qgame.vap.core.spi.VideoRenderConfig
import com.tencent.qgame.vap.core.spi.splitLegacyVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyVideoLayoutTest {
    @Test fun horizontalSplitPreservesOddPackedWidthAndChannelOrder() {
        val left = VideoRect(0, 0, 3, 9)
        val right = VideoRect(3, 0, 3, 9)

        assertEquals(VideoRenderConfig(3, 9, 7, 9, right, left), splitLegacyVideo(7, 9, false, false))
        assertEquals(VideoRenderConfig(3, 9, 7, 9, left, right), splitLegacyVideo(7, 9, false, true))
    }

    @Test fun verticalSplitPreservesOddPackedHeightAndChannelOrder() {
        val top = VideoRect(0, 0, 7, 4)
        val bottom = VideoRect(0, 4, 7, 4)

        assertEquals(VideoRenderConfig(7, 4, 7, 9, bottom, top), splitLegacyVideo(7, 9, true, false))
        assertEquals(VideoRenderConfig(7, 4, 7, 9, top, bottom), splitLegacyVideo(7, 9, true, true))
    }
}
