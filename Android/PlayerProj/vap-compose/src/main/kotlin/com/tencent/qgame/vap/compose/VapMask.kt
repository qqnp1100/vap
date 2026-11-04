package com.tencent.qgame.vap.compose

import android.graphics.Bitmap
import com.tencent.qgame.vap.core.spi.MaskRegion
import com.tencent.qgame.vap.core.spi.SurfaceMask
import com.tencent.qgame.vap.core.spi.VideoRect

data class VapMaskRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val referenceWidth: Int,
    val referenceHeight: Int,
)

/** Keep [bitmap] valid and immutable for the duration of its use by the player. */
data class VapMask(
    val bitmap: Bitmap,
    val position: VapMaskRegion? = null,
    val texture: VapMaskRegion? = null,
    val edgeBlur: Boolean = false,
)

internal fun VapMask.toCore() = SurfaceMask(bitmap, position?.toCore(), texture?.toCore(), edgeBlur)

private fun VapMaskRegion.toCore() =
    MaskRegion(VideoRect(x, y, width, height), referenceWidth, referenceHeight)
