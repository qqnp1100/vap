package com.tencent.qgame.vap.core.spi

/** Video rectangle in physical output pixels. The origin is the output's top left. */
data class SurfaceGeometry(
    val outputWidth: Int,
    val outputHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(outputWidth > 0 && outputHeight > 0 && width > 0 && height > 0)
    }

    /** Returns normalized coordinates inside the video and the visible output. */
    fun toVideoPoint(x: Float, y: Float): Pair<Float, Float>? {
        if (!(x >= 0f && y >= 0f && x < outputWidth && y < outputHeight)) return null
        val videoX = (x.toDouble() - left) / width
        val videoY = (y.toDouble() - top) / height
        return if (videoX >= 0.0 && videoY >= 0.0 && videoX < 1.0 && videoY < 1.0) {
            videoX.toFloat() to videoY.toFloat()
        } else null
    }

    /** Intersection of the video and output in GL's bottom-left coordinates. */
    internal val videoScissor: VideoRect = run {
        val x = left.coerceIn(0, outputWidth)
        val y = top.coerceIn(0, outputHeight)
        val right = (left.toLong() + width).coerceIn(0L, outputWidth.toLong()).toInt()
        val bottom = (top.toLong() + height).coerceIn(0L, outputHeight.toLong()).toInt()
        VideoRect(x, outputHeight - bottom, right - x, bottom - y)
    }

    /** Maps alternating clip-space x/y vertices into the same host-sized viewport. */
    internal fun transformVertices(vertices: FloatArray): FloatArray {
        val scaleX = width.toDouble() / outputWidth
        val scaleY = height.toDouble() / outputHeight
        val shiftX = 2.0 * left / outputWidth + scaleX - 1.0
        val shiftY = 1.0 - 2.0 * top / outputHeight - scaleY
        for (index in vertices.indices step 2) {
            vertices[index] = (vertices[index] * scaleX + shiftX).toFloat()
            vertices[index + 1] = (vertices[index + 1] * scaleY + shiftY).toFloat()
        }
        return vertices
    }
}
