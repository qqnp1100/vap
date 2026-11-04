package com.tencent.qgame.vap.compose

import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tencent.qgame.vap.core.spi.SurfaceGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceLayoutTest {
    @Test
    fun fitAndCropShareTheSameOutputSize() {
        val video = IntSize(200, 100)
        val output = IntSize(100, 100)

        assertEquals(SurfaceGeometry(100, 100, 0, 25, 100, 50), SurfaceLayout().resolve(video, output))
        assertEquals(
            SurfaceGeometry(100, 100, -50, 0, 200, 100),
            SurfaceLayout(contentScale = ContentScale.Crop).resolve(video, output),
        )
    }

    @Test
    fun startAlignmentUsesLayoutDirection() {
        val video = IntSize(100, 200)
        val output = IntSize(100, 100)
        val layout = SurfaceLayout(alignment = Alignment.CenterStart)

        assertEquals(0, layout.resolve(video, output).left)
        assertEquals(50, layout.copy(direction = LayoutDirection.Rtl).resolve(video, output).left)
    }
}
