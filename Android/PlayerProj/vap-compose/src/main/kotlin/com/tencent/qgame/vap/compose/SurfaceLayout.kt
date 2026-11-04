package com.tencent.qgame.vap.compose

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tencent.qgame.vap.core.spi.SurfaceGeometry
import kotlin.math.roundToInt

internal data class SurfaceLayout(
    val contentScale: ContentScale = ContentScale.Fit,
    val alignment: Alignment = Alignment.Center,
    val direction: LayoutDirection = LayoutDirection.Ltr,
) {
    fun resolve(video: IntSize, output: IntSize): SurfaceGeometry {
        if (video.width <= 0 || video.height <= 0) {
            return SurfaceGeometry(output.width, output.height, 0, 0, output.width, output.height)
        }
        val scale = contentScale.computeScaleFactor(
            Size(video.width.toFloat(), video.height.toFloat()),
            Size(output.width.toFloat(), output.height.toFloat()),
        )
        val content = IntSize(
            (video.width * scale.scaleX).roundToInt().coerceAtLeast(1),
            (video.height * scale.scaleY).roundToInt().coerceAtLeast(1),
        )
        val offset = alignment.align(content, output, direction)
        return SurfaceGeometry(output.width, output.height, offset.x, offset.y, content.width, content.height)
    }
}
