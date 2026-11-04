package com.tencent.qgame.vap.compose

import com.tencent.qgame.vap.core.spi.SurfaceMixResourceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceProviderAdapterTest {
    private val request = SurfaceMixResourceRequest("id", "tag", 1, 1, 1, 1)

    @Test
    fun providerTimeoutCompletesBothRequestsWithTheOriginalFailure() {
        val provider = object : VapResourceProvider {
            override suspend fun loadImage(request: VapResourceRequest): VapImageLease? =
                withTimeout(0) { awaitCancellation() }

            override suspend fun loadText(request: VapResourceRequest): String? =
                withTimeout(0) { awaitCancellation() }
        }
        val adapter = ResourceProviderAdapter(CoroutineScope(Dispatchers.Unconfined), provider)
        var imageFailure: Throwable? = null
        var textFailure: Throwable? = null
        adapter.loadImage(request) { imageFailure = it.exceptionOrNull() }
        adapter.loadText(request) { textFailure = it.exceptionOrNull() }
        assertTrue(imageFailure is TimeoutCancellationException)
        assertTrue(textFailure is TimeoutCancellationException)
    }

    @Test
    fun playerCancellationDoesNotDeliverAResult() {
        var cancelled = false
        val provider = object : VapResourceProvider {
            override suspend fun loadImage(request: VapResourceRequest): VapImageLease? {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val adapter = ResourceProviderAdapter(CoroutineScope(Dispatchers.Unconfined), provider)
        var delivered = false
        val cancel = adapter.loadImage(request) { delivered = true }
        cancel()
        assertTrue(cancelled)
        assertFalse(delivered)
    }
}
