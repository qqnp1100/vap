package com.tencent.qgame.vap.compose

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.tencent.qgame.vap.core.model.VapSource
import com.tencent.qgame.vap.core.spi.SurfaceMixResourceProvider
import com.tencent.qgame.vap.core.spi.SurfacePlaybackEvent
import com.tencent.qgame.vap.core.spi.SurfacePlaybackOptions
import com.tencent.qgame.vap.core.spi.createSurfacePlayback
import com.tencent.qgame.vap.core.spi.openCachedAssetInput
import com.tencent.qgame.vap.core.spi.openFileInput
import com.tencent.qgame.vap.core.spi.openUriInput
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** All host operations and event delivery run on the main thread. */
internal class SurfaceHost(
    private val context: Context,
    private val state: VapPlaybackState,
    private val onEvent: (VapEvent) -> Unit,
) {
    class Output(val surface: Surface, var width: Int, var height: Int)

    private data class RequestKey(
        val source: VapSource,
        val playbackKey: Long,
        val iterations: Int,
        val audioEnabled: Boolean,
        val resourceKey: Long,
    )

    private data class Request(val key: RequestKey, val id: Long, val resources: SurfaceMixResourceProvider?)
    private class Session(val previouslyCompleted: Int, var token: Long? = null, var hasFrame: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val playback = createSurfacePlayback()
    private val resourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Request? = null
    private var output: Output? = null
    private var session: Session? = null
    private var started = false
    private var active = false
    private var playing = true
    private var muted = true
    private var mask: VapMask? = null
    private var layout = SurfaceLayout()
    private var completedIterations = 0
    private var terminal = false
    private var bound = false
    private var disposed = false

    var videoSize by mutableStateOf(IntSize.Zero)
        private set

    fun bind() {
        bound = state.bind(this)
        if (!bound) main.post {
            if (!disposed) {
                dispatch(VapEvent.Failed(0, invalidRequest(IllegalStateException("VapPlaybackState already has a host"))))
            }
        }
    }

    fun updateRequest(
        source: VapSource,
        playbackKey: Long,
        iterations: Int,
        audioEnabled: Boolean,
        resourceKey: Long,
        resourceProvider: VapResourceProvider?,
        isPlaying: Boolean,
        isActive: Boolean,
        isMuted: Boolean,
        mask: VapMask?,
    ) {
        if (disposed || !bound) return
        val key = RequestKey(source, playbackKey, iterations, audioEnabled, resourceKey)
        if (key != request?.key) {
            stop("request")
            request = Request(key, nextId.incrementAndGet(), resourceProvider?.let {
                ResourceProviderAdapter(resourceScope, it)
            })
            terminal = false
            completedIterations = 0
            videoSize = IntSize.Zero
            if (iterations != -1 && iterations <= 0) {
                fail(invalidRequest(IllegalArgumentException("iterations must be positive or -1")))
                return
            }
        }
        val playingChanged = playing != isPlaying
        val mutedChanged = muted != isMuted
        val maskChanged = this.mask != mask
        active = isActive
        playing = isPlaying
        muted = isMuted
        this.mask = mask
        session?.token?.let { token ->
            if (playingChanged) playback.setPlaying(token, playing)
            if (mutedChanged) playback.setMuted(token, muted)
            if (maskChanged) playback.setMask(token, mask?.toCore())
        }
        reconcile()
    }

    fun setStarted(value: Boolean) {
        if (disposed || !bound) return
        started = value
        reconcile()
    }

    fun updateLayout(contentScale: ContentScale, alignment: Alignment, direction: LayoutDirection) {
        val next = SurfaceLayout(contentScale, alignment, direction)
        if (next == layout) return
        layout = next
        updateGeometry()
    }

    fun attach(target: Output) {
        if (disposed) return
        if (output !== target) {
            stop("surface-replaced")
            output = target
        }
        reconcile()
    }

    fun resize(target: Output, width: Int, height: Int) {
        if (disposed || output !== target) return
        if (target.width == width && target.height == height) return
        target.width = width
        target.height = height
        updateGeometry()
        reconcile()
    }

    fun destroy(target: Output) {
        if (output !== target) return
        output = null
        stop("surface-destroyed")
        reconcile()
    }

    fun click(x: Float, y: Float): Boolean {
        val id = request?.id ?: return false
        val token = session?.token ?: return false
        val hit = playback.hitTest(token, x, y) ?: return false
        dispatch(VapEvent.ResourceClick(id, hit.sourceId, hit.tag))
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        output = null
        stop("composition-disposed")
        resourceScope.cancel()
        playback.release()
        state.unbind(this)
    }

    private fun reconcile() {
        val current = request ?: return
        if (disposed || !bound || terminal) return
        val target = output
        if (!active || !started || target == null || target.width <= 0 || target.height <= 0) {
            stop("host-inactive")
            publish(if (started && active && completedIterations == 0 && videoSize == IntSize.Zero)
                VapPlaybackPhase.AwaitingSurface else VapPlaybackPhase.Suspended)
            return
        }
        val existing = session
        if (existing != null || !playing) {
            publish(when {
                !playing -> VapPlaybackPhase.Paused
                existing?.hasFrame == true -> VapPlaybackPhase.Playing
                else -> VapPlaybackPhase.Preparing
            })
            return
        }

        val iterations = current.key.iterations
        val remaining = if (iterations == -1) -1 else iterations - completedIterations
        if (remaining == 0) {
            complete()
            return
        }
        val owner = Session(completedIterations)
        session = owner
        publish(VapPlaybackPhase.Preparing)
        try {
            owner.token = playback.start(
                target.surface, target.width, target.height,
                openInput = {
                    when (val source = current.key.source) {
                        is VapSource.Asset -> openCachedAssetInput(context.assets, source.path, context.cacheDir)
                        is VapSource.File -> openFileInput(source.file)
                        is VapSource.Uri -> openUriInput(context.contentResolver, source.uri)
                    }
                },
                options = SurfacePlaybackOptions(
                    iterations = remaining,
                    isPlaying = playing,
                    audioEnabled = current.key.audioEnabled,
                    isMuted = muted,
                    resourceProvider = current.resources,
                    mask = mask?.toCore(),
                    waitForGeometry = true,
                ),
            ) { event -> main.post { receive(owner, event) } }
            updateGeometry()
        } catch (error: Exception) {
            session = null
            fail(invalidRequest(error))
        }
    }

    private fun receive(owner: Session, event: SurfacePlaybackEvent) {
        val id = request?.id ?: return
        if (disposed || session !== owner || terminal) return
        when (event) {
            is SurfacePlaybackEvent.Configured -> {
                videoSize = IntSize(event.width, event.height)
                updateGeometry()
            }
            SurfacePlaybackEvent.Started -> {
                owner.hasFrame = true
                publish(if (playing) VapPlaybackPhase.Playing else VapPlaybackPhase.Paused)
                dispatch(VapEvent.Started(id))
            }
            is SurfacePlaybackEvent.IterationCompleted -> {
                recordIterations(owner, event.count)
                publish(if (playing) VapPlaybackPhase.Playing else VapPlaybackPhase.Paused)
            }
            SurfacePlaybackEvent.Completed -> complete()
            is SurfacePlaybackEvent.Failed -> fail(event.error.toPublicError())
        }
    }

    private fun updateGeometry() {
        val token = session?.token ?: return
        val target = output ?: return
        if (target.width > 0 && target.height > 0 && videoSize != IntSize.Zero) {
            playback.setGeometry(token, layout.resolve(videoSize, IntSize(target.width, target.height)))
        }
    }

    private fun complete() {
        val current = request ?: return
        terminal = true
        publish(VapPlaybackPhase.Completed)
        dispatch(VapEvent.Completed(current.id))
    }

    private fun fail(error: VapPlaybackError) {
        val current = request ?: return
        terminal = true
        publish(VapPlaybackPhase.Failed, error)
        dispatch(VapEvent.Failed(current.id, error))
    }

    private fun publish(phase: VapPlaybackPhase, error: VapPlaybackError? = null) {
        val current = request ?: return
        state.update(this, VapPlaybackSnapshot(current.id, phase, completedIterations, error))
    }

    private fun stop(reason: String) {
        val owner = session
        session = null
        val token = owner?.token
        if (token != null) {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            playback.detach(token)?.let { recordIterations(owner, it) }
            val durationUs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000
            Log.i("VapComposeSurface", "detach reason=$reason elapsedUs=$durationUs")
        }
    }

    private fun recordIterations(owner: Session, count: Int) {
        val total = (owner.previouslyCompleted.toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        completedIterations = maxOf(completedIterations, total)
    }

    private fun dispatch(event: VapEvent) {
        try {
            onEvent(event)
        } catch (error: Exception) {
            Log.e("VapComposeSurface", "Playback event callback failed", error)
        }
    }

    private companion object {
        val nextId = AtomicLong()
    }
}
