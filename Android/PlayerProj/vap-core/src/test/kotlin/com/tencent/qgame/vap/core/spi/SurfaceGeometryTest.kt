package com.tencent.qgame.vap.core.spi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceGeometryTest {
    @Test
    fun fitAppliesTheSameLetterboxToDrawAndHitCoordinates() {
        val geometry = SurfaceGeometry(200, 200, 0, 50, 200, 100)
        val quad = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        assertArrayEquals(floatArrayOf(-1f, .5f, -1f, -.5f, 1f, .5f, 1f, -.5f),
            geometry.transformVertices(quad), 0.00001f)
        assertNull(geometry.toVideoPoint(100f, 49f))
        assertNull(geometry.toVideoPoint(100f, 150f))
        assertEquals(.5f to .5f, geometry.toVideoPoint(100f, 100f))
    }

    @Test
    fun cropClipsToOutputAndInvertsTheVisibleSubset() {
        val geometry = SurfaceGeometry(200, 100, -100, 0, 400, 100)
        val quad = floatArrayOf(-1f, 1f, 1f, -1f)
        assertArrayEquals(floatArrayOf(-2f, 1f, 2f, -1f), geometry.transformVertices(quad), 0.00001f)
        assertNull(geometry.toVideoPoint(-1f, 50f))
        assertNull(geometry.toVideoPoint(200f, 50f))
        assertEquals(.25f to .5f, geometry.toVideoPoint(0f, 50f))
    }

    @Test
    fun scissorClipsOversizedOverlaysToTheSameAreaAsHitTesting() {
        val geometry = SurfaceGeometry(200, 200, 25, 20, 150, 100)
        assertEquals(VideoRect(25, 80, 150, 100), geometry.videoScissor)
        assertNull(geometry.toVideoPoint(100f, 19f))
        assertNull(geometry.toVideoPoint(100f, 120f))
        assertEquals(.5f to 0f, geometry.toVideoPoint(100f, 20f))
    }

    @Test
    fun scissorHandlesCropAndVideoEntirelyOutsideTheOutput() {
        assertEquals(VideoRect(0, 0, 200, 100), SurfaceGeometry(200, 100, -50, -10, 300, 150).videoScissor)
        assertEquals(VideoRect(200, 0, 0, 0),
            SurfaceGeometry(200, 100, Int.MAX_VALUE, Int.MAX_VALUE, 300, 150).videoScissor)
    }
}
