package com.tencent.qgame.vap.compose

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean

data class VapResourceRequest(
    val sourceId: String,
    val tag: String,
    val width: Int,
    val height: Int,
    val targetWidth: Int,
    val targetHeight: Int,
)

/** The caller owns the bitmap. The player closes this lease when the bitmap is no longer needed. */
class VapImageLease(val bitmap: Bitmap, private val onClose: () -> Unit = {}) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

interface VapResourceProvider {
    suspend fun loadImage(request: VapResourceRequest): VapImageLease? = null
    suspend fun loadText(request: VapResourceRequest): String? = null
}
