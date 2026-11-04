package com.tencent.qgame.vap.core.spi

import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.os.Handler
import com.tencent.qgame.animplayer.YuvFrame
import com.tencent.qgame.vap.core.internal.codec.VideoDecoder

enum class VideoDecodeFailure { EXTRACTOR, DECODE, CREATE_RENDER, UNSUPPORTED_HEVC, RENDER }

/** Media, render and session ports for the shared decoder. Versioned with the host libraries. */
interface VideoDecodeHost<R : Any> {
    val sessionId: Long
    val enableVersion1: Boolean
    val isOutputAvailable: Boolean
    val renderHandler: Handler?
    val decodeHandler: Handler?
    var isDecoderRunning: Boolean
    var isStopRequested: Boolean
    var output: R?

    fun isSessionActive(id: Long): Boolean
    fun prepareRender(needYuv: Boolean): Boolean
    fun preparePlay(width: Int, height: Int)
    fun externalTexture(output: R): Int
    fun clearFrame(output: R)
    fun renderPackedFrame(output: R, texture: SurfaceTexture)
    fun renderYuvFrame(output: R, frame: YuvFrame)
    fun swapBuffers(output: R)
    fun releaseTextures(output: R)
    fun destroyRender(output: R)
    fun destroyThreads(id: Long)
    fun preRender(ptsUs: Long, cancelled: () -> Boolean)
    fun resetClock()
    fun consumeVideoLoop(): Int
    fun stopPlayback()

    fun onVideoStart()
    fun onDecoding(index: Int)
    fun onRendering(index: Int)
    fun onRelease()
    fun onDestroy()
    fun onPlaybackComplete(id: Long)
    fun onWorkerUnavailable(id: Long)
    fun onFailure(failure: VideoDecodeFailure, detail: String, cause: Throwable? = null)

    val isDebug: Boolean
    fun debug(message: String)
    fun info(message: String)
    fun error(message: String, cause: Throwable)
    fun mediaInfo(message: String)
    fun mediaError(cause: Throwable)
    fun cleanupError(operation: String, cause: Throwable)
}

interface VideoDecodeController : SurfaceTexture.OnFrameAvailableListener {
    fun start(openExtractor: () -> MediaExtractor)
    fun destroy()
    fun releaseSurfaceWhenDone(release: (() -> Unit)?)
}

fun <R : Any> createVideoDecoder(host: VideoDecodeHost<R>): VideoDecodeController = VideoDecoder(host)
