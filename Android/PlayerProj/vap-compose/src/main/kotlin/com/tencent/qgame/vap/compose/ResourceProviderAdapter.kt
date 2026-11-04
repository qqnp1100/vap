package com.tencent.qgame.vap.compose

import com.tencent.qgame.vap.core.spi.SurfaceMixImage
import com.tencent.qgame.vap.core.spi.SurfaceMixResourceProvider
import com.tencent.qgame.vap.core.spi.SurfaceMixResourceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class ResourceProviderAdapter(
    private val scope: CoroutineScope,
    private val provider: VapResourceProvider,
) : SurfaceMixResourceProvider {
    override fun loadImage(
        request: SurfaceMixResourceRequest,
        complete: (Result<SurfaceMixImage?>) -> Unit,
    ): () -> Unit {
        val job = scope.launch {
            var lease: VapImageLease? = null
            val result = try {
                lease = provider.loadImage(request.toPublicRequest())
                ensureActive()
                Result.success(lease?.let { SurfaceMixImage(it.bitmap, it::close) })
            } catch (error: Exception) {
                try {
                    lease?.close()
                } catch (closeError: Exception) {
                    if (closeError !== error) error.addSuppressed(closeError)
                }
                ensureActive()
                Result.failure(error)
            }
            complete(result)
        }
        return { job.cancel() }
    }

    override fun loadText(
        request: SurfaceMixResourceRequest,
        complete: (Result<String?>) -> Unit,
    ): () -> Unit {
        val job = scope.launch {
            val result = try {
                val value = provider.loadText(request.toPublicRequest())
                ensureActive()
                Result.success(value)
            } catch (error: Exception) {
                ensureActive()
                Result.failure(error)
            }
            complete(result)
        }
        return { job.cancel() }
    }

    private fun SurfaceMixResourceRequest.toPublicRequest() =
        VapResourceRequest(sourceId, tag, width, height, targetWidth, targetHeight)
}
