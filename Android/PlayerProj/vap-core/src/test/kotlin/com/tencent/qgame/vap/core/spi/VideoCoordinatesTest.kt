package com.tencent.qgame.vap.core.spi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoCoordinatesTest {
    @Test fun vertexCoordinatesConvertTopLeftRectToGlStrip() {
        val vertices = FloatArray(8)
        val result = VertexUtil.create(100, 50, VideoRect(25, 10, 50, 20), vertices)

        assertSame(vertices, result)
        assertArrayEquals(
            floatArrayOf(-0.5f, 0.6f, -0.5f, -0.2f, 0.5f, 0.6f, 0.5f, -0.2f),
            result,
            0.00001f
        )
    }

    @Test fun textureCoordinatesRotateLocalRectClockwise() {
        val coordinates = FloatArray(8)
        TexCoordsUtil.create(100, 50, VideoRect(25, 10, 50, 20), coordinates)
        assertArrayEquals(
            floatArrayOf(0.25f, 0.2f, 0.25f, 0.6f, 0.75f, 0.2f, 0.75f, 0.6f),
            coordinates,
            0.00001f
        )

        assertSame(coordinates, TexCoordsUtil.rotate90(coordinates))
        assertArrayEquals(
            floatArrayOf(0.25f, 0.6f, 0.75f, 0.6f, 0.25f, 0.2f, 0.75f, 0.2f),
            coordinates,
            0.00001f
        )
    }
}
