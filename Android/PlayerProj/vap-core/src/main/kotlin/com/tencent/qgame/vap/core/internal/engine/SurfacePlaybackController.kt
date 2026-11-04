package com.tencent.qgame.vap.core.internal.engine

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.tencent.qgame.animplayer.YuvFrame
import com.tencent.qgame.vap.core.internal.codec.SurfaceAudioPlayback
import com.tencent.qgame.vap.core.internal.codec.SurfaceCodecUnavailableException
import com.tencent.qgame.vap.core.internal.codec.SurfaceDecodeTiming
import com.tencent.qgame.vap.core.internal.codec.SurfaceFrameSyncTimeoutException
import com.tencent.qgame.vap.core.internal.codec.SurfaceMediaConfigException
import com.tencent.qgame.vap.core.internal.codec.VideoDecoder
import com.tencent.qgame.vap.core.internal.mix.SurfaceMaskSession
import com.tencent.qgame.vap.core.internal.mix.SurfaceMixSession
import com.tencent.qgame.vap.core.internal.render.Egl14Window
import com.tencent.qgame.vap.core.internal.render.PackedVideoRenderer
import com.tencent.qgame.vap.core.spi.CoreMediaAccess
import com.tencent.qgame.vap.core.spi.RenderErrorLogger
import com.tencent.qgame.vap.core.spi.SourceNotSeekableException
import com.tencent.qgame.vap.core.spi.SurfaceGeometry
import com.tencent.qgame.vap.core.spi.SurfaceMask
import com.tencent.qgame.vap.core.spi.SurfaceMixHit
import com.tencent.qgame.vap.core.spi.SurfacePlayback
import com.tencent.qgame.vap.core.spi.SurfacePlaybackErrorKind
import com.tencent.qgame.vap.core.spi.SurfacePlaybackEvent
import com.tencent.qgame.vap.core.spi.SurfacePlaybackFailure
import com.tencent.qgame.vap.core.spi.SurfacePlaybackOptions
import com.tencent.qgame.vap.core.spi.SurfacePlaybackStage
import com.tencent.qgame.vap.core.spi.UriSourceAccessException
import com.tencent.qgame.vap.core.spi.VapConfigFields
import com.tencent.qgame.vap.core.spi.VapMediaInput
import com.tencent.qgame.vap.core.spi.VideoDecodeFailure
import com.tencent.qgame.vap.core.spi.VideoDecodeHost
import com.tencent.qgame.vap.core.spi.VideoRenderConfig
import com.tencent.qgame.vap.core.spi.decodeVapConfig
import com.tencent.qgame.vap.core.spi.parseVapConfig
import com.tencent.qgame.vap.core.spi.readVapConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@TargetApi(23)
internal class SurfacePlaybackController : SurfacePlayback {
    private val sessions = PlaybackSessionCoordinator<Session>(SessionAdmission.ReplaceLatest)
    private var lastFinished: FinishedSession? = null
    private var released = false

    override fun start(
        surface: Surface, width: Int, height: Int, openInput: () -> VapMediaInput,
        options: SurfacePlaybackOptions,
        onEvent: (SurfacePlaybackEvent) -> Unit
    ): Long {
        require(width > 0 && height > 0)
        require(options.iterations == -1 || options.iterations > 0) { "Invalid playback iterations" }
        val geometry = options.initialGeometry ?: fillGeometry(width, height)
        require(geometry.outputWidth == width && geometry.outputHeight == height)
        val selection = synchronized(sessions.lock) {
            check(!released) { "Playback controller was released" }
            lastFinished = null
            checkNotNull(sessions.select { lifetime ->
                Session(lifetime, surface, geometry, openInput, options, onEvent)
            })
        }
        selection.superseded?.source?.revoke()
        launchRequested()
        return selection.session.id
    }

    override fun resize(token: Long, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        findSession(token)?.updateGeometry(fillGeometry(width, height), explicit = false)
    }

    override fun setGeometry(token: Long, geometry: SurfaceGeometry) {
        findSession(token)?.updateGeometry(geometry, explicit = true)
    }

    override fun setPlaying(token: Long, isPlaying: Boolean) {
        findSession(token)?.setPlaying(isPlaying)
    }

    override fun setMuted(token: Long, isMuted: Boolean) {
        findSession(token)?.setMuted(isMuted)
    }

    override fun setMask(token: Long, mask: SurfaceMask?) {
        findSession(token)?.setMask(mask)
    }

    override fun hitTest(token: Long, x: Float, y: Float): SurfaceMixHit? =
        findSession(token)?.hitTest(x, y)

    private fun findSession(token: Long): Session? = sessions.selected(token)?.source

    override fun detach(token: Long): Int? {
        val session = synchronized(sessions.lock) {
            val active = sessions.withdraw(token)?.source
            if (active == null) {
                val finished = lastFinished?.takeIf { it.token == token } ?: return null
                lastFinished = null
                return finished.iterations
            }
            active
        }
        session.revoke()
        return session.completedIterations()
    }

    override fun release() {
        val session = synchronized(sessions.lock) {
            released = true
            lastFinished = null
            sessions.withdraw()?.source
        }
        session?.revoke()
    }

    private fun launchRequested() {
        sessions.takeNext()?.source?.launch()
    }

    private data class FinishedSession(val token: Long, val iterations: Int)

    private fun fillGeometry(width: Int, height: Int) =
        SurfaceGeometry(width, height, 0, 0, width, height)

    private inner class Session(
        private val lifetime: PlaybackSessionCoordinator.Lifetime,
        surface: Surface,
        initialGeometry: SurfaceGeometry,
        private val openInput: () -> VapMediaInput,
        private val options: SurfacePlaybackOptions,
        private val observer: (SurfacePlaybackEvent) -> Unit
    ) : VideoDecodeHost<PackedVideoRenderer>, SurfaceDecodeTiming {
        override val sessionId get() = lifetime.id
        private val geometry = AtomicReference(initialGeometry)
        private val geometryReady = CountDownLatch(
            if (options.waitForGeometry && options.initialGeometry == null) 1 else 0
        )
        private val mask = AtomicReference(options.mask)
        private val redrawPending = AtomicBoolean()
        private val borrowed = AtomicReference<Surface?>(surface)
        private val outputDetached = CountDownLatch(1)
        private val failure = AtomicReference<SurfacePlaybackFailure?>()
        private val clock = SurfaceVideoClock(options.isPlaying)
        private val decoder = VideoDecoder(this, surfaceTiming = this)
        private val logger = RenderErrorLogger { tag, message, cause -> Log.e(tag, message, cause) }
        private val maskRenderer = SurfaceMaskSession(logger)
        private var renderThread: HandlerThread? = null
        private var decodeThread: HandlerThread? = null
        private var input: VapMediaInput? = null
        private var config: VideoRenderConfig? = null
        @Volatile private var mix: SurfaceMixSession? = null
        @Volatile private var audio: SurfaceAudioPlayback? = null
        @Volatile private var playing = options.isPlaying
        @Volatile private var muted = options.isMuted
        private var appliedGeometry: SurfaceGeometry? = null
        private val presentedLock = Any()
        private var presentedGeometry: SurfaceGeometry? = null
        private var lastFrameIndex: Int? = null
        private var frameDurationUs = 0L
        private val iterationLock = Any()
        private var iterationsCompleted = 0
        private var decodingStarted = false
        private var started = false
        private val cancelled get() = lifetime.cancelled

        @Volatile override var renderHandler: Handler? = null
        @Volatile override var decodeHandler: Handler? = null
        @Volatile override var isDecoderRunning = false
        @Volatile override var isStopRequested = false
        override var output: PackedVideoRenderer? = null
        override val isOutputAvailable get() = borrowed.get() != null
        override val enableVersion1 = false
        override val isDebug = false

        fun launch() {
            if (cancelled) {
                finish()
                return
            }
            try {
                val worker = object : HandlerThread("vap_surface_render") {
                    override fun onLooperPrepared() = startDecodeWorker(looper)
                }
                renderThread = worker
                worker.start()
            } catch (error: Throwable) {
                recordFailure(SurfacePlaybackErrorKind.DecoderFailure, SurfacePlaybackStage.Decode, error)
                finish()
            }
        }

        private fun startDecodeWorker(renderLooper: Looper) {
            try {
                renderHandler = Handler(renderLooper)
                if (cancelled) {
                    finish()
                    return
                }
                val worker = object : HandlerThread("vap_surface_decode") {
                    override fun onLooperPrepared() {
                        decodeHandler = Handler(looper)
                        prepare()
                    }
                }
                decodeThread = worker
                worker.start()
            } catch (error: Throwable) {
                recordFailure(SurfacePlaybackErrorKind.DecoderFailure, SurfacePlaybackStage.Decode, error)
                finish()
            }
        }

        private fun prepare() {
            var stage = SurfacePlaybackStage.Source
            try {
                if (cancelled) {
                    finish()
                    return
                }
                val source = openInput().also { input = it }
                stage = SurfacePlaybackStage.Config
                val bytes = requireNotNull(readVapConfig(source)) { "Playback requires a VAP v2 vapc box" }
                val fields = VapConfigFields()
                val json = decodeVapConfig(bytes)
                val error = parseVapConfig(json, fields)
                if (error != null) throw IllegalArgumentException(error.message ?: "Invalid VAP v2 configuration", error.cause)
                val video = VideoRenderConfig(fields.width, fields.height, fields.videoWidth, fields.videoHeight,
                    checkNotNull(fields.rgb), checkNotNull(fields.alpha))
                config = video
                frameDurationUs = 1_000_000L / (fields.fps.takeIf { it > 0 } ?: 30)
                emit(SurfacePlaybackEvent.Configured(fields.width, fields.height))
                geometryReady.await()
                if (cancelled) {
                    finish()
                    return
                }
                if (fields.isMix) {
                    val currentGeometry = geometry.get()
                    val fusion = SurfaceMixSession(
                        json, video, currentGeometry.width, currentGeometry.height, options.resourceProvider
                    )
                    mix = fusion
                    stage = SurfacePlaybackStage.Resource
                    fusion.prepare(sessionId, checkNotNull(decodeHandler), ::isSessionActive) { error ->
                        if (error != null) {
                            recordResourceFailure(error)
                            finish()
                        } else startDecoding(source)
                    }
                } else {
                    startDecoding(source)
                }
            } catch (error: Throwable) {
                when {
                    error is SourceNotSeekableException || error is UriSourceAccessException ||
                        stage == SurfacePlaybackStage.Source ->
                        recordSourceFailure(error)
                    stage == SurfacePlaybackStage.Resource -> recordResourceFailure(error)
                    else -> recordFailure(SurfacePlaybackErrorKind.InvalidVapConfig, SurfacePlaybackStage.Config, error)
                }
                finish()
            }
        }

        private fun startDecoding(source: VapMediaInput) {
            if (decodingStarted) return
            if (!lifetime.isActive) {
                finish()
                return
            }
            try {
                if (!clock.awaitPlaying { !isSessionActive(sessionId) }) {
                    finish()
                    return
                }
                val openExtractor = {
                    CoreMediaAccess.openExtractor(source::setDataSource) { cleanupError("release extractor", it) }
                }
                if (options.audioEnabled) {
                    val worker = SurfaceAudioPlayback(openExtractor, { isSessionActive(sessionId) }, clock) { error ->
                        recordFailure(SurfacePlaybackErrorKind.AudioFailure, SurfacePlaybackStage.Audio, error)
                        stopPlayback()
                    }
                    audio = worker
                    worker.setMuted(muted)
                    worker.setPaused(!playing)
                }
                if (!sessions.markReady(sessionId)) {
                    finish()
                    return
                }
                decodingStarted = true
                decoder.start(openExtractor)
            } catch (error: Throwable) {
                recordFailure(SurfacePlaybackErrorKind.DecoderFailure, SurfacePlaybackStage.Decode, error)
                finish()
            }
        }

        fun updateGeometry(value: SurfaceGeometry, explicit: Boolean) {
            val changed = geometry.getAndSet(value) != value
            if (explicit) geometryReady.countDown()
            if (changed) scheduleRedraw()
        }

        fun setPlaying(value: Boolean) {
            playing = value
            audio?.setPaused(!value)
            clock.setPlaying(value)
        }

        fun setMuted(value: Boolean) {
            muted = value
            audio?.setMuted(value)
        }

        fun setMask(value: SurfaceMask?) {
            if (mask.getAndSet(value) != value) scheduleRedraw()
        }

        fun hitTest(x: Float, y: Float): SurfaceMixHit? = synchronized(presentedLock) {
            val point = presentedGeometry?.toVideoPoint(x, y) ?: return@synchronized null
            mix?.hit(point.first, point.second)
        }

        private fun scheduleRedraw() {
            if (!redrawPending.compareAndSet(false, true)) return
            val posted = renderHandler?.post {
                redrawPending.set(false)
                if (!isSessionActive(sessionId) || isStopRequested || !isOutputAvailable) return@post
                val target = output ?: return@post
                val frame = lastFrameIndex ?: return@post
                try {
                    applyGeometry(target)
                    target.renderFrame()
                    renderOverlays(target, frame)
                    swapBuffers(target)
                } catch (error: Throwable) {
                    recordFailure(SurfacePlaybackErrorKind.RenderFailure, SurfacePlaybackStage.Render, error)
                    stopPlayback()
                }
            }
            if (posted != true) redrawPending.set(false)
        }

        fun revoke() {
            synchronized(iterationLock) { isStopRequested = true }
            geometryReady.countDown()
            clock.wake()
            audio?.setPaused(true)
            if (borrowed.getAndSet(null) != null) {
                val handler = renderHandler
                if (handler == null) {
                    // No renderer could have observed the Surface without a render handler.
                    outputDetached.countDown()
                } else if (handler.looper == Looper.myLooper()) {
                    detachWindow()
                } else {
                    // If the looper already stopped, finish() has acknowledged output destruction.
                    handler.postAtFrontOfQueue(::detachWindow)
                }
            }
            awaitDetach()
            decodeHandler?.post { if (!decodingStarted) finish() }
        }

        private fun detachWindow() {
            try {
                output?.detachOutput()
            } catch (error: Throwable) {
                cleanupError("detach EGL window", error)
                try {
                    output?.destroyRender()
                } catch (releaseError: Throwable) {
                    cleanupError("destroy failed EGL window", releaseError)
                }
            } finally {
                outputDetached.countDown()
            }
        }

        private fun awaitDetach() {
            var interrupted = false
            while (true) {
                try {
                    outputDetached.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }

        private fun finish() {
            sessions.finish(
                sessionId,
                beforeIdle = {
                    borrowed.set(null)
                    outputDetached.countDown()
                },
                whenIdle = { finish ->
                    val worker = audio
                    if (worker == null) finish()
                    else {
                        worker.destroy()
                        worker.releaseWhenIdle(finish)
                    }
                },
                close = { closeInputAndWorkers() },
                onRetired = { _, wasSelected ->
                    if (wasSelected) lastFinished = FinishedSession(sessionId, completedIterations())
                },
                onTerminal = {
                    val error = failure.get()
                    emit(if (error == null) SurfacePlaybackEvent.Completed else SurfacePlaybackEvent.Failed(error))
                    launchRequested()
                },
            )
        }

        private fun closeInputAndWorkers() {
            try {
                mix?.close()
            } catch (error: Throwable) {
                cleanupError("release VAPX resources", error)
            }
            mix = null
            try {
                input?.close()
            } catch (error: Throwable) {
                recordFailure(SurfacePlaybackErrorKind.SourceAccess, SurfacePlaybackStage.Release, error)
            }
            input = null
            decodeThread?.quitSafely()
            renderThread?.quitSafely()
        }

        private fun emit(event: SurfacePlaybackEvent) {
            if (cancelled) return
            try {
                observer(event)
            } catch (error: Throwable) {
                cleanupError("Surface playback observer", error)
            }
        }

        override fun isSessionActive(id: Long) = id == sessionId && lifetime.isActive

        override fun prepareRender(needYuv: Boolean): Boolean {
            val surface = borrowed.get() ?: return false
            output = PackedVideoRenderer(surface, logger, ::Egl14Window)
            return true
        }

        override fun preparePlay(width: Int, height: Int) {
            val video = checkNotNull(config)
            if (width != video.videoWidth || height != video.videoHeight) {
                throw SurfaceMediaConfigException(
                    "Codec dimensions ${width}×$height do not match packed VAP dimensions ${video.videoWidth}×${video.videoHeight}"
                )
            }
            checkNotNull(output).setConfig(video)
        }

        override fun externalTexture(output: PackedVideoRenderer) = output.getExternalTexture()
        override fun clearFrame(output: PackedVideoRenderer) {
            if (isOutputAvailable) output.clearFrame()
        }

        override fun renderPackedFrame(output: PackedVideoRenderer, texture: SurfaceTexture) {
            applyGeometry(output)
            output.updateTextureTransform(texture)
            output.renderFrame()
        }

        private fun applyGeometry(output: PackedVideoRenderer) {
            val currentGeometry = geometry.get()
            if (appliedGeometry != currentGeometry) {
                output.updateGeometry(currentGeometry)
                appliedGeometry = currentGeometry
            }
        }

        override fun renderYuvFrame(output: PackedVideoRenderer, frame: YuvFrame) = Unit

        override fun swapBuffers(output: PackedVideoRenderer) {
            if (!isOutputAvailable) return
            output.swapBuffers()
            synchronized(presentedLock) {
                mix?.presented()
                presentedGeometry = appliedGeometry
            }
            if (!started) {
                started = true
                emit(SurfacePlaybackEvent.Started)
            }
        }

        override fun releaseTextures(output: PackedVideoRenderer) = output.releaseTexture()
        override fun destroyRender(output: PackedVideoRenderer) = output.destroyRender()
        override fun destroyThreads(id: Long) = Unit
        override fun preRender(ptsUs: Long, cancelled: () -> Boolean) {
            clock.awaitPresentation(ptsUs, cancelled) { audio?.positionUs() }
            if (!cancelled()) audio?.recordVideoSubmission(ptsUs)
        }

        override fun resetClock() = clock.reset()
        override fun startIteration(firstVideoPtsUs: Long) {
            val worker = audio
            if (worker == null) clock.startIteration(firstVideoPtsUs, null)
            else worker.startIteration(firstVideoPtsUs)
        }

        override fun endIteration(lastPtsUs: Long, cancelled: () -> Boolean) {
            clock.awaitPresentation(lastPtsUs + frameDurationUs, cancelled) { audio?.positionUs() }
            audio?.stopIteration()
        }

        fun completedIterations(): Int = synchronized(iterationLock) { iterationsCompleted }

        override fun consumeVideoLoop(): Int {
            val completed = synchronized(iterationLock) {
                if (cancelled || isStopRequested) return 0
                if (iterationsCompleted < Int.MAX_VALUE) iterationsCompleted++
                iterationsCompleted
            }
            emit(SurfacePlaybackEvent.IterationCompleted(completed))
            return if (options.iterations == -1) 1 else options.iterations - completed
        }

        override fun stopPlayback() {
            isStopRequested = true
            clock.wake()
        }
        override fun onVideoStart() = Unit
        override fun onDecoding(index: Int) = Unit
        override fun onRendering(index: Int) {
            lastFrameIndex = index
            renderOverlays(checkNotNull(output), index)
        }

        private fun renderOverlays(renderer: PackedVideoRenderer, index: Int) {
            val currentGeometry = checkNotNull(appliedGeometry)
            val video = checkNotNull(config)
            val clip = currentGeometry.videoScissor
            val needsClip = clip.w != currentGeometry.outputWidth || clip.h != currentGeometry.outputHeight
            if (needsClip) {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(clip.x, clip.y, clip.w, clip.h)
            }
            try {
                mix?.render(renderer, index, currentGeometry)
                maskRenderer.render(mask.get(), video.width, video.height, currentGeometry)
            } finally {
                if (needsClip) GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
        }

        override fun onRelease() {
            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    recordFailure(SurfacePlaybackErrorKind.RenderFailure, SurfacePlaybackStage.Release, error)
                }
            }
            if (output == null) return
            release { mix?.releaseGl() }
            release { maskRenderer.releaseGl() }
            release { output?.let(::clearFrame) }
        }
        override fun onDestroy() = Unit
        override fun onPlaybackComplete(id: Long) = finish()
        override fun onWorkerUnavailable(id: Long) {
            recordFailure(SurfacePlaybackErrorKind.DecoderFailure, SurfacePlaybackStage.Decode,
                IllegalStateException("Playback worker unavailable"))
            finish()
        }
        override fun onFailure(failure: VideoDecodeFailure, detail: String, cause: Throwable?) {
            val error = cause ?: IllegalStateException("$failure: $detail")
            when {
                cause is SurfaceFrameSyncTimeoutException -> recordFailure(
                    SurfacePlaybackErrorKind.FrameSyncTimeout, SurfacePlaybackStage.Render, cause
                )
                cause is SurfaceMediaConfigException -> recordFailure(
                    SurfacePlaybackErrorKind.InvalidVapConfig, SurfacePlaybackStage.Config, cause
                )
                failure == VideoDecodeFailure.EXTRACTOR -> recordSourceFailure(error)
                cause is SurfaceCodecUnavailableException || failure == VideoDecodeFailure.UNSUPPORTED_HEVC -> recordFailure(
                    SurfacePlaybackErrorKind.UnsupportedCodec, SurfacePlaybackStage.Decode, error
                )
                failure == VideoDecodeFailure.RENDER || failure == VideoDecodeFailure.CREATE_RENDER -> recordFailure(
                    SurfacePlaybackErrorKind.RenderFailure, SurfacePlaybackStage.Render, error
                )
                else -> recordFailure(SurfacePlaybackErrorKind.DecoderFailure, SurfacePlaybackStage.Decode, error)
            }
        }

        private fun recordSourceFailure(error: Throwable) {
            val kind = if (error is SourceNotSeekableException) SurfacePlaybackErrorKind.SourceNotSeekable
                else SurfacePlaybackErrorKind.SourceAccess
            val cause = if (error is UriSourceAccessException) error.cause ?: error else error
            recordFailure(kind, SurfacePlaybackStage.Source, cause)
        }

        private fun recordResourceFailure(error: Throwable) {
            val kind = if (error is TimeoutException) SurfacePlaybackErrorKind.ResourceTimeout
                else SurfacePlaybackErrorKind.ResourceLoad
            recordFailure(kind, SurfacePlaybackStage.Resource, error)
        }

        private fun recordFailure(kind: SurfacePlaybackErrorKind, stage: SurfacePlaybackStage, cause: Throwable) {
            failure.compareAndSet(null, SurfacePlaybackFailure(kind, stage, cause))
        }

        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun error(message: String, cause: Throwable) { Log.e(TAG, message, cause) }
        override fun mediaInfo(message: String) = Unit
        override fun mediaError(cause: Throwable) = error("codec discovery", cause)
        override fun cleanupError(operation: String, cause: Throwable) = error(operation, cause)
    }

    private companion object {
        const val TAG = "VapSurfacePlayback"
    }
}
