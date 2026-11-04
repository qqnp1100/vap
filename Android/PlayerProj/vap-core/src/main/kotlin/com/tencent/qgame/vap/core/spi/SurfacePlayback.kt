package com.tencent.qgame.vap.core.spi

import android.annotation.TargetApi
import android.view.Surface
import com.tencent.qgame.vap.core.internal.engine.SurfacePlaybackController

sealed interface SurfacePlaybackEvent {
    data class Configured(val width: Int, val height: Int) : SurfacePlaybackEvent
    object Started : SurfacePlaybackEvent
    data class IterationCompleted(val count: Int) : SurfacePlaybackEvent
    object Completed : SurfacePlaybackEvent
    data class Failed(val error: SurfacePlaybackFailure) : SurfacePlaybackEvent {
        val cause: Throwable get() = error.cause
    }
}

enum class SurfacePlaybackErrorKind {
    InvalidRequest, SourceAccess, SourceNotSeekable, InvalidVapConfig, UnsupportedCodec,
    ResourceTimeout, ResourceLoad, DecoderFailure, RenderFailure, FrameSyncTimeout, AudioFailure
}

enum class SurfacePlaybackStage { Request, Source, Config, Resource, Decode, Render, Audio, Release }

data class SurfacePlaybackFailure(
    val kind: SurfacePlaybackErrorKind,
    val stage: SurfacePlaybackStage,
    val cause: Throwable,
)

data class SurfacePlaybackOptions(
    val iterations: Int = 1,
    val isPlaying: Boolean = true,
    val audioEnabled: Boolean = true,
    val isMuted: Boolean = true,
    val resourceProvider: SurfaceMixResourceProvider? = null,
    val mask: SurfaceMask? = null,
    val initialGeometry: SurfaceGeometry? = null,
    /** Waits after Configured until the host provides the first video rectangle. */
    val waitForGeometry: Boolean = false,
)

/**
 * V2 playback on a borrowed window. The host serializes commands on its own thread.
 * Event callbacks run on core workers and must return without waiting for host commands.
 */
interface SurfacePlayback {
    fun start(
        surface: Surface,
        width: Int,
        height: Int,
        openInput: () -> VapMediaInput,
        options: SurfacePlaybackOptions = SurfacePlaybackOptions(),
        onEvent: (SurfacePlaybackEvent) -> Unit
    ): Long

    fun resize(token: Long, width: Int, height: Int)

    fun setGeometry(token: Long, geometry: SurfaceGeometry)

    fun setPlaying(token: Long, isPlaying: Boolean)

    fun setMuted(token: Long, isMuted: Boolean)

    fun setMask(token: Long, mask: SurfaceMask?)

    fun hitTest(token: Long, x: Float, y: Float): SurfaceMixHit?

    /**
     * Synchronously stops display access and returns the committed iteration count, if known.
     * Media cleanup continues independently.
     */
    fun detach(token: Long): Int?

    fun release()
}

@TargetApi(23)
fun createSurfacePlayback(): SurfacePlayback = SurfacePlaybackController()
