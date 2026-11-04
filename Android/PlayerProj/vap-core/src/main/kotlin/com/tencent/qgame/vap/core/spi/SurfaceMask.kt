package com.tencent.qgame.vap.core.spi

import android.graphics.Bitmap

/** Borrowed alpha bitmap, immutable until the host replaces the mask. */
class SurfaceMask(
    val bitmap: Bitmap,
    val position: MaskRegion? = null,
    val texture: MaskRegion? = null,
    val edgeBlur: Boolean = false
)
