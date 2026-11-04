package com.tencent.qgame.vap.core.internal.mix

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.Handler
import android.util.Log
import com.tencent.qgame.vap.core.spi.MixBitmaps
import com.tencent.qgame.vap.core.spi.MixConfigReader
import com.tencent.qgame.vap.core.spi.MixFrameLayer
import com.tencent.qgame.vap.core.spi.MixGeometry
import com.tencent.qgame.vap.core.spi.MixLayerRenderer
import com.tencent.qgame.vap.core.spi.MixResourceLoader
import com.tencent.qgame.vap.core.spi.MixSourceDefinition
import com.tencent.qgame.vap.core.spi.PackedRenderer
import com.tencent.qgame.vap.core.spi.RenderErrorLogger
import com.tencent.qgame.vap.core.spi.SurfaceGeometry
import com.tencent.qgame.vap.core.spi.SurfaceMixHit
import com.tencent.qgame.vap.core.spi.SurfaceMixImage
import com.tencent.qgame.vap.core.spi.SurfaceMixResourceProvider
import com.tencent.qgame.vap.core.spi.SurfaceMixResourceRequest
import com.tencent.qgame.vap.core.spi.TextureLoadUtil
import com.tencent.qgame.vap.core.spi.VideoRect
import com.tencent.qgame.vap.core.spi.VideoRenderConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One VAPX request. Prepare on the media worker, render and release GL on the GL worker. */
@TargetApi(23)
internal class SurfaceMixSession(
    json: JSONObject,
    private val config: VideoRenderConfig,
    displayWidth: Int,
    displayHeight: Int,
    private val provider: SurfaceMixResourceProvider?
) {
    private class Source(val definition: MixSourceDefinition) {
        var bitmap: Bitmap? = null
        var text = ""
        var texture = 0
        var drawWidth = 0
        var drawHeight = 0
        val isText get() = definition.sourceType == "txt"
        val centerFull get() = definition.fitType == "centerFull"
    }

    private val sources = LinkedHashMap<String, Source>()
    private val frames = HashMap<Int, List<MixFrameLayer>>()
    private val loader = MixResourceLoader()
    private val cancellations = ArrayList<() -> Unit>()
    private val imageLeases = ArrayList<SurfaceMixImage>()
    private val firstFailure = AtomicReference<Throwable?>()
    private val closed = AtomicBoolean()
    private val renderer = MixLayerRenderer()
    private val logger = RenderErrorLogger { tag, message, cause -> Log.e(tag, message, cause) }
    private val displaySizes: Map<String, Pair<Int, Int>>

    private var retainedPixels = 0L
    private var maxTextureSize = 0
    private var drawn: List<MixFrameLayer> = emptyList()
    private var drawableFrames: Map<Int, List<MixFrameLayer>> = emptyMap()
    @Volatile private var presented: List<MixFrameLayer> = emptyList()

    init {
        val frameCount = json.getJSONObject("info").getInt("f")
        MixConfigReader.forEachSource(json) { definition ->
            require(definition.sourceType == "txt" || definition.sourceType == "img") {
                "Unsupported VAPX resource type: ${definition.sourceType}"
            }
            require(sources.put(definition.id, Source(definition)) == null) {
                "Duplicate VAPX resource: ${definition.id}"
            }
        }
        MixConfigReader.forEachFrame(json) { frame ->
            require(frame.index in 0 until frameCount && frames.put(frame.index, frame.layers) == null) {
                "Invalid or duplicate VAPX frame: ${frame.index}"
            }
            for (layer in frame.layers) {
                require(layer.sourceId in sources) { "Unknown VAPX resource: ${layer.sourceId}" }
                require(layer.maskRotation == 0 || layer.maskRotation == 90) {
                    "Unsupported VAPX mask rotation: ${layer.maskRotation}"
                }
                require(layer.videoMask.inside(config.videoWidth, config.videoHeight)) { "Invalid VAPX video mask" }
                require(layer.position.w > 0 && layer.position.h > 0) { "Invalid VAPX layer dimensions" }
            }
        }
        displaySizes = MixGeometry.targetSizes(
            frames.values.asSequence().flatten().map { it.sourceId to it.position }, sources.keys,
            config.width, config.height, displayWidth, displayHeight
        )
    }

    fun prepare(id: Long, handler: Handler, isActive: (Long) -> Boolean, complete: (Throwable?) -> Unit) {
        val resourceProvider = provider ?: run {
            complete(null)
            return
        }
        val requests = sources.values.map { source ->
            val definition = source.definition
            val target = displaySizes[definition.id]
            val request = SurfaceMixResourceRequest(
                definition.id, definition.tag, definition.width, definition.height,
                target?.first ?: definition.width, target?.second ?: definition.height
            )
            if (source.isText) {
                val received = AtomicBoolean()
                MixResourceLoader.Request.Text(fetch = { callback ->
                    resourceProvider.loadText(request) { result ->
                        if (received.compareAndSet(false, true)) {
                            result.exceptionOrNull()?.let { firstFailure.compareAndSet(null, it) }
                            callback(result.getOrNull())
                        }
                    }?.let(cancellations::add)
                }, accept = { source.text = it })
            } else {
                imageRequest(resourceProvider, request, source)
            }
        }
        loader.load(id, handler, requests, RESOURCE_TIMEOUT_MS, MAX_CONCURRENT_REQUESTS, true, isActive, prepareBitmaps = {
            if (firstFailure.get() != null) false else runCatching {
                for (source in sources.values) {
                    val def = source.definition
                    if (source.isText && source.text.isNotEmpty()) {
                        require(source.text.length <= MAX_TEXT_CHARS) { "VAPX text exceeds the character budget" }
                        require(reservePixels(def.width, def.height)) { "VAPX bitmaps exceed the total pixel budget" }
                        source.bitmap = MixBitmaps.createTextBitmap(
                            def.width, def.height, source.text, def.style == "b", def.color
                        )
                    }
                    source.bitmap?.let { bitmap ->
                        val (width, height) = drawSize(def, bitmap, source.centerFull)
                        source.drawWidth = width
                        source.drawHeight = height
                    }
                }
                drawableFrames = frames.mapValues { (_, layers) -> layers.filter { sources.getValue(it.sourceId).bitmap != null } }
            }.onFailure { firstFailure.compareAndSet(null, it) }.isSuccess
        }, onError = { firstFailure.compareAndSet(null, it) }) { success ->
            complete(if (success) null else firstFailure.get() ?: IllegalStateException("VAPX resources could not be loaded"))
        }
    }

    private fun imageRequest(
        provider: SurfaceMixResourceProvider, request: SurfaceMixResourceRequest, source: Source
    ): MixResourceLoader.Request.Image {
        var accepted: SurfaceMixImage? = null
        var received = false
        val lock = Any()
        return MixResourceLoader.Request.Image(fetch = { callback ->
            provider.loadImage(request) { result ->
                val lease = result.getOrNull()
                val first = synchronized(lock) {
                    if (received) false else {
                        received = true
                        accepted = lease
                        true
                    }
                }
                if (!first) {
                    if (synchronized(lock) { lease !== accepted }) lease?.closeSafely()
                } else {
                    result.exceptionOrNull()?.let { firstFailure.compareAndSet(null, it) }
                    val bitmap = lease?.bitmap
                    if (bitmap != null && !bitmap.validImage()) {
                        firstFailure.compareAndSet(null, IllegalArgumentException("Unsupported VAPX bitmap for ${request.sourceId}"))
                        lease.closeSafely()
                        callback(null)
                    } else callback(bitmap)
                }
            }?.let(cancellations::add)
        }, accept = { bitmap ->
            if (bitmap != null) {
                val lease = synchronized(lock) { accepted }
                if (reservePixels(bitmap.width, bitmap.height)) {
                    source.bitmap = bitmap
                    lease?.let(imageLeases::add)
                } else {
                    firstFailure.compareAndSet(null, IllegalArgumentException("VAPX bitmaps exceed the total pixel budget"))
                    lease?.closeSafely()
                }
            }
        }, discard = {
            synchronized(lock) { accepted }?.closeSafely()
        })
    }

    fun render(video: PackedRenderer, frameIndex: Int, geometry: SurfaceGeometry? = null) {
        drawn = drawableFrames[frameIndex].orEmpty()
        if (drawn.isEmpty()) return
        renderer.geometry = geometry
        if (renderer.shader == null) {
            renderer.init(logger, premultiplied = true)
            check(renderer.shader?.program != 0) { "Could not initialize VAPX shader" }
            val limit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
            maxTextureSize = limit[0]
        }
        for (layer in drawn) {
            val source = sources.getValue(layer.sourceId)
            val bitmap = checkNotNull(source.bitmap)
            if (source.texture == 0) {
                check(bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize) {
                    "VAPX bitmap exceeds GL_MAX_TEXTURE_SIZE"
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                source.texture = TextureLoadUtil.loadTexture(bitmap, logger)
                check(source.texture != 0) { "Could not upload VAPX bitmap" }
            }
            renderer.renderFrame(
                video, config.width, config.height, config.videoWidth, config.videoHeight,
                layer.position, layer.videoMask, layer.maskRotation == 90, source.drawWidth, source.drawHeight,
                source.centerFull, source.texture, false, 0
            )
        }
    }

    fun presented() {
        presented = drawn
    }

    fun hit(x: Float, y: Float): SurfaceMixHit? {
        if (x !in 0f..<1f || y !in 0f..<1f) return null
        val logicalX = x * config.width
        val logicalY = y * config.height
        val layers = presented
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            val rect = layer.position
            if (logicalX >= rect.x && logicalY >= rect.y &&
                logicalX < rect.x.toLong() + rect.w && logicalY < rect.y.toLong() + rect.h
            ) {
                val source = sources.getValue(layer.sourceId).definition
                return SurfaceMixHit(source.id, source.tag)
            }
        }
        return null
    }

    fun releaseGl() {
        presented = emptyList()
        drawn = emptyList()
        sources.values.forEach { source ->
            renderer.release(source.texture)
            source.texture = 0
        }
        renderer.releaseShader()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        presented = emptyList()
        loader.cancel()
        cancellations.forEach { cancel -> runCatching(cancel).onFailure(::logCloseFailure) }
        imageLeases.forEach { it.closeSafely() }
        sources.values.forEach { source ->
            if (source.isText) source.bitmap?.recycle()
            source.bitmap = null
        }
    }

    private fun SurfaceMixImage.closeSafely() {
        runCatching(::close).onFailure(::logCloseFailure)
    }

    private fun logCloseFailure(error: Throwable) {
        Log.e("VapSurfaceMix", "VAPX resource release failed", error)
    }

    private fun Bitmap.validImage() = !isRecycled && config != Bitmap.Config.ALPHA_8 &&
        (android.os.Build.VERSION.SDK_INT < 26 || config != Bitmap.Config.HARDWARE) &&
        (!hasAlpha() || isPremultiplied) && width.toLong() * height <= MAX_PIXELS

    private fun reservePixels(width: Int, height: Int): Boolean {
        val pixels = width.toLong() * height
        if (pixels > MAX_PIXELS - retainedPixels) return false
        retainedPixels += pixels
        return true
    }

    private fun drawSize(source: MixSourceDefinition, bitmap: Bitmap, centerFull: Boolean): Pair<Int, Int> {
        if (!centerFull) return bitmap.width to bitmap.height
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height
        val sourceRatio = source.width.toFloat() / source.height
        return if (bitmapRatio >= sourceRatio) (source.height * bitmapRatio).toInt().coerceAtLeast(1) to source.height
        else source.width to (source.width / bitmapRatio).toInt().coerceAtLeast(1)
    }

    private fun VideoRect.inside(width: Int, height: Int) = x >= 0 && y >= 0 && w > 0 && h > 0 &&
        x.toLong() + w <= width && y.toLong() + h <= height

    private companion object {
        const val RESOURCE_TIMEOUT_MS = 10_000L
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_PIXELS = 16_777_216L
        const val MAX_TEXT_CHARS = 4096
    }
}
