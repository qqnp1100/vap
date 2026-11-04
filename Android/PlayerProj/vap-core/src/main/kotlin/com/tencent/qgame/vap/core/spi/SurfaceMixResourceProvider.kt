package com.tencent.qgame.vap.core.spi

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

data class SurfaceMixResourceRequest(
    val sourceId: String,
    val tag: String,
    val width: Int,
    val height: Int,
    val targetWidth: Int,
    val targetHeight: Int
)

/** An immutable bitmap borrowed for this playback request. */
class SurfaceMixImage(val bitmap: Bitmap, private val release: () -> Unit = {}) {
    private val closed = AtomicBoolean()

    fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Dispatch asynchronous work and return a cancellation callback, if available. */
interface SurfaceMixResourceProvider {
    fun loadImage(
        request: SurfaceMixResourceRequest,
        complete: (Result<SurfaceMixImage?>) -> Unit
    ): (() -> Unit)?

    fun loadText(
        request: SurfaceMixResourceRequest,
        complete: (Result<String?>) -> Unit
    ): (() -> Unit)?
}

data class SurfaceMixHit(val sourceId: String, val tag: String)
