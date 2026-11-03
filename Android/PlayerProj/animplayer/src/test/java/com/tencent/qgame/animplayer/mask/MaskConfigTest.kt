package com.tencent.qgame.animplayer.mask

import com.tencent.qgame.animplayer.PointRect
import com.tencent.qgame.animplayer.RefVec2
import org.junit.Assert.*
import org.junit.Test

class MaskConfigTest {
    @Test fun equalGeometryHasSymmetricEqualityAndMatchingHashCodes() {
        val first = config()
        val second = config()
        assertEquals(first, first)
        assertEquals(first, second)
        assertEquals(second, first)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, hashSetOf(first, second).size)
    }

    @Test fun changingEitherGeometryFieldChangesEquality() {
        val first = config()
        val changedTexture = config().apply { maskTexPair = PointRect(1, 0, 20, 20) to RefVec2(20, 20) }
        val changedPosition = config().apply { maskPositionPair = PointRect(0, 0, 10, 20) to RefVec2(20, 20) }
        assertNotEquals(first, changedTexture)
        assertNotEquals(first, changedPosition)
        assertFalse(first.equals(null))
    }

    @Test fun repeatedReleaseClearsGeometryWithoutCreatingGlObjects() {
        val config = config()
        repeat(3) { config.release() }
        assertNull(config.alphaMaskBitmap)
        assertNull(config.maskPositionPair)
        assertNull(config.maskTexPair)
        assertEquals(0, config.getMaskTexId())
    }

    private fun config() = MaskConfig().apply {
        maskTexPair = PointRect(0, 0, 20, 20) to RefVec2(20, 20)
        maskPositionPair = PointRect(0, 0, 20, 20) to RefVec2(20, 20)
    }
}
