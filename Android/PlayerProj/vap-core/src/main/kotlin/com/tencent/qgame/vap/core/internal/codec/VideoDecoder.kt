/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.qgame.vap.core.internal.codec

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.tencent.qgame.animplayer.YuvFrame
import com.tencent.qgame.animplayer.YuvFramePool
import com.tencent.qgame.animplayer.YuvPlaneCopy
import com.tencent.qgame.vap.core.internal.engine.SurfaceFrameTracker
import com.tencent.qgame.vap.core.spi.CoreMediaAccess
import com.tencent.qgame.vap.core.spi.VideoDecodeController
import com.tencent.qgame.vap.core.spi.VideoDecodeFailure
import com.tencent.qgame.vap.core.spi.VideoDecodeHost
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

internal interface SurfaceDecodeTiming {
    fun startIteration(firstVideoPtsUs: Long)
    fun endIteration(lastPtsUs: Long, cancelled: () -> Boolean)
}

internal class SurfaceFrameSyncTimeoutException :
    IllegalStateException("Timed out waiting for the last EGL swap")

internal class SurfaceCodecUnavailableException(mime: String, cause: Throwable? = null) :
    IllegalArgumentException("Unsupported video codec: $mime", cause)

internal class SurfaceMediaConfigException(message: String) : IllegalArgumentException(message)

internal fun runSurfaceRenderSetup(post: (Runnable) -> Boolean, action: () -> Unit) {
    val task = FutureTask(action, Unit)
    check(post(task)) { "Render worker unavailable" }
    try {
        task.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}

internal class VideoDecoder<R : Any>(
    private val host: VideoDecodeHost<R>,
    private val surfaceTiming: SurfaceDecodeTiming? = null,
) : VideoDecodeController {
    private val setupOnDecodeThread get() = surfaceTiming != null
    private var isRunning: Boolean
        get() = host.isDecoderRunning
        set(value) { host.isDecoderRunning = value }

    private var isStopReq: Boolean
        get() = host.isStopRequested
        set(value) { host.isStopRequested = value }

    private fun supportsHevc() = CoreMediaAccess.checkSupportCodec(
        CoreMediaAccess.MIME_HEVC, host::mediaInfo, host::mediaError
    )

    private inline fun safeCall(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            host.cleanupError(operation, error)
        }
    }

    private var surface: Surface? = null
    @Volatile private var glTexture: SurfaceTexture? = null
    private val bufferInfo by lazy { MediaCodec.BufferInfo() }
    @Volatile private var activeSessionId = 0L
    private val releaseQueued = AtomicBoolean()
    private val failureReported = AtomicBoolean()
    private val renderPending = AtomicBoolean()
    private var surfaceFrames: SurfaceFrameTracker? = null
    private var lastLatchedTimestamp = Long.MIN_VALUE
    private val surfaceReleases = ArrayList<() -> Unit>()

    // 动画的原始尺寸
    private var videoWidth = 0
    private var videoHeight = 0

    private var needYUV = false
    private var yuvFrames: YuvFramePool? = null


    override fun start(openExtractor: () -> MediaExtractor) {
        activeSessionId = host.sessionId
        releaseQueued.set(false)
        failureReported.set(false)
        isStopReq = false
        isRunning = true
        val startupHandler = if (setupOnDecodeThread) host.decodeHandler else host.renderHandler
        val posted = startupHandler?.post {
            if (host.isSessionActive(activeSessionId)) startPlay(openExtractor)
            else release(null, null)
        }
        if (posted != true) {
            isRunning = false
            host.onWorkerUnavailable(activeSessionId)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        val id = activeSessionId
        if (surfaceTexture == null || isStopReq || surfaceTexture !== glTexture || !host.isSessionActive(id)) return
        if (!renderPending.compareAndSet(false, true)) return
        val posted = host.renderHandler?.post {
            // Let a callback arriving during update/draw schedule the next latch.
            renderPending.set(false)
            if (!canRender(id) || surfaceTexture !== glTexture) return@post
            try {
                surfaceTexture.updateTexImage()
                val timestamp = surfaceTexture.timestamp
                val useTimestamp = Build.VERSION.SDK_INT >= 21
                if (useTimestamp && timestamp == lastLatchedTimestamp) return@post
                lastLatchedTimestamp = timestamp
                val index = checkNotNull(surfaceFrames).latch(timestamp, useTimestamp)
                val target = checkNotNull(host.output)
                host.renderPackedFrame(target, surfaceTexture)
                host.onRendering(index ?: -1)
                if (host.isDebug) {
                    host.debug("Surface frame timestampNs=$timestamp index=$index")
                }
                host.swapBuffers(target)
                if (setupOnDecodeThread && index != null && canRender(id)) {
                    surfaceFrames?.presented(timestamp)
                }
            } catch (e: Throwable) {
                failRendering(id, e)
            }
        }
        if (posted != true) renderPending.set(false)
    }

    private fun canRender(id: Long): Boolean =
        !isStopReq && host.isSessionActive(id) && host.isOutputAvailable

    private fun failRendering(id: Long, error: Throwable) {
        if (canRender(id)) failPlayback(id, VideoDecodeFailure.RENDER, error)
    }

    private fun failPlayback(id: Long, failure: VideoDecodeFailure, error: Throwable) {
        if (!host.isSessionActive(id) || !failureReported.compareAndSet(false, true)) return
        // Decode owns its in-flight codec calls. Stop it and let it enqueue the normal release.
        host.stopPlayback()
        host.onFailure(failure, error.toString(), error)
    }

    private fun startPlay(openExtractor: () -> MediaExtractor) {

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var format: MediaFormat? = null
        var trackIndex = 0

        try {
            extractor = openExtractor()
            trackIndex = CoreMediaAccess.selectTrack(extractor, "video/") { host.info(it) }
            if (trackIndex < 0) {
                throw RuntimeException("No video track found")
            }
            extractor.selectTrack(trackIndex)
            format = extractor.getTrackFormat(trackIndex)
            if (format == null) throw RuntimeException("format is null")
            if (setupOnDecodeThread && format.containsKey(MediaFormat.KEY_ROTATION) &&
                format.getInteger(MediaFormat.KEY_ROTATION) != 0) {
                throw SurfaceMediaConfigException("Rotated codec output is unsupported for packed VAP video")
            }

            // 是否支持h265
            if (CoreMediaAccess.isHevc(format)) {
                if (Build.VERSION.SDK_INT  < Build.VERSION_CODES.LOLLIPOP
                    || !supportsHevc()) {

                    host.onFailure(VideoDecodeFailure.UNSUPPORTED_HEVC,
                        "sdk:${Build.VERSION.SDK_INT},support hevc:" + supportsHevc())
                    release(null, extractor)
                    return
                }
            }

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            host.info("Video size is $videoWidth x $videoHeight")

            // 由于使用mediacodec解码老版本素材时对宽度1500尺寸的视频进行数据对齐，解码后的宽度变成1504，导致采样点出现偏差播放异常
            // 所以当开启兼容老版本视频模式并且老版本视频的宽度不能被16整除时要走YUV渲染逻辑
            // 但是这样直接判断有风险，后期想办法改
            needYUV = videoWidth % 16 != 0 && host.enableVersion1
            yuvFrames = if (needYUV) YuvFramePool(videoWidth, videoHeight) else null

            try {
                onRenderThread {
                    if (setupOnDecodeThread) {
                        check(host.isSessionActive(activeSessionId)) { "Playback cancelled during setup" }
                    }
                    if (!host.prepareRender(needYUV)) {
                        throw RuntimeException("render create fail")
                    }
                    host.preparePlay(videoWidth, videoHeight)
                    if (!needYUV) {
                        surfaceFrames = SurfaceFrameTracker()
                        lastLatchedTimestamp = Long.MIN_VALUE
                        glTexture = SurfaceTexture(host.externalTexture(checkNotNull(host.output)))
                        glTexture?.setOnFrameAvailableListener(this)
                        glTexture?.setDefaultBufferSize(videoWidth, videoHeight)
                    }
                    host.output?.let(host::clearFrame)
                }
            } catch (t: Throwable) {
                host.onFailure(VideoDecodeFailure.CREATE_RENDER, "e=$t", t.takeIf { setupOnDecodeThread })
                release(null, extractor)
                return
            }

        } catch (e: Throwable) {
            host.error("MediaExtractor exception e=$e", e)
            host.onFailure(VideoDecodeFailure.EXTRACTOR, "e=$e", e.takeIf { setupOnDecodeThread })
            release(decoder, extractor)
            return
        }

        try {
            if (setupOnDecodeThread && !host.isSessionActive(activeSessionId)) {
                release(null, extractor)
                return
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            host.info("Video MIME is $mime")
            if (setupOnDecodeThread && mime != "video/avc" && mime != CoreMediaAccess.MIME_HEVC) {
                throw SurfaceCodecUnavailableException(mime)
            }
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: IllegalArgumentException) {
                if (setupOnDecodeThread) throw SurfaceCodecUnavailableException(mime, error)
                throw error
            }
            decoder = codec // Own it before configure/start, either of which may throw.
            if (needYUV) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                codec.configure(format, null, null, 0)
            } else {
                surface = Surface(glTexture)
                codec.configure(format, surface, null, 0)
            }
            codec.start()
            if (host.decodeHandler?.post {
                try {
                    startDecode(extractor, codec)
                } catch (e: Throwable) {
                    failPlayback(activeSessionId, VideoDecodeFailure.DECODE, e)
                    release(codec, extractor)
                }
            } != true) release(codec, extractor)
        } catch (e: Throwable) {
            host.error("MediaCodec configure exception e=$e", e)
            host.onFailure(VideoDecodeFailure.DECODE, "e=$e", e.takeIf { setupOnDecodeThread })
            release(decoder, extractor)
            return
        }
    }

    private fun onRenderThread(action: () -> Unit) {
        if (setupOnDecodeThread) runSurfaceRenderSetup({ host.renderHandler?.post(it) == true }, action)
        else action()
    }

    private fun startDecode(extractor: MediaExtractor ,decoder: MediaCodec) {
        val TIMEOUT_USEC = 10000L
        var inputChunk = 0
        var outputDone = false
        var inputDone = false
        var frameIndex = 0
        var isLoop = false
        var lastPtsUs: Long? = null

        val decoderInputBuffers = if (!setupOnDecodeThread || Build.VERSION.SDK_INT < 21) decoder.inputBuffers else null
        var outputBuffers = if (needYUV && Build.VERSION.SDK_INT < 21) decoder.outputBuffers else null
        var outputFormat: MediaFormat? = null
        val frames = surfaceFrames
        val cancelled = { isStopReq || !host.isSessionActive(activeSessionId) }

        while (!outputDone) {
            if (isStopReq || !host.isSessionActive(activeSessionId)) {
                host.info("stop decode")
                release(decoder, extractor)
                return
            }

            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufIndex >= 0) {
                    val inputBuf = if (decoderInputBuffers == null && Build.VERSION.SDK_INT >= 21) {
                        checkNotNull(decoder.getInputBuffer(inputBufIndex))
                    } else checkNotNull(decoderInputBuffers)[inputBufIndex]
                    val chunkSize = extractor.readSampleData(inputBuf, 0)
                    if (chunkSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        host.debug("decode EOS")
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0)
                        host.debug("submitted frame $inputChunk to dec, size=$chunkSize")
                        inputChunk++
                        extractor.advance()
                    }
                } else {
                    host.debug("input buffer not available")
                }
            }

            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                when {
                    decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> host.debug("no output from decoder available")
                    decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        if (needYUV && Build.VERSION.SDK_INT < 21) outputBuffers = decoder.outputBuffers
                    }
                    decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        host.info("decoder output format changed: $outputFormat")
                    }
                    decoderStatus < 0 -> {
                        throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                    }
                    else -> {
                        var loop = 0
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (eos && !setupOnDecodeThread) {
                            loop = host.consumeVideoLoop()
                            outputDone = loop <= 0
                        }
                        val doRender = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            (bufferInfo.size > 0 || (!needYUV && !eos))
                        if (doRender) {
                            if (lastPtsUs == null) surfaceTiming?.startIteration(bufferInfo.presentationTimeUs)
                            host.preRender(bufferInfo.presentationTimeUs, cancelled)
                            if (cancelled()) {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                                release(decoder, extractor)
                                return
                            }
                            if (setupOnDecodeThread) lastPtsUs = bufferInfo.presentationTimeUs
                        }

                        if (needYUV) {
                            try {
                                if (doRender) yuvProcess(decoder, decoderStatus, outputBuffers, outputFormat, frameIndex)
                            } finally {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                            }
                        } else if (doRender) {
                            val tracker = checkNotNull(frames)
                            val timestamp = tracker.submit(System.nanoTime(), frameIndex)
                            if (Build.VERSION.SDK_INT >= 21) {
                                decoder.releaseOutputBuffer(decoderStatus, timestamp)
                            } else {
                                decoder.releaseOutputBuffer(decoderStatus, true)
                            }
                        } else {
                            decoder.releaseOutputBuffer(decoderStatus, false)
                        }

                        if (doRender || (!setupOnDecodeThread && !needYUV)) {
                            if (frameIndex == 0 && !isLoop) host.onVideoStart()
                            host.onDecoding(frameIndex)
                            frameIndex++
                        }
                        // Drain the latest submitted image at EOS. On old APIs, keep only one
                        // submitted image in flight because the codec cannot tag its timestamp.
                        if (!setupOnDecodeThread && frames != null &&
                            (eos || (doRender && Build.VERSION.SDK_INT < 21)) &&
                            !frames.awaitLatest(cancelled)) {
                            failPlayback(activeSessionId, VideoDecodeFailure.RENDER,
                                IllegalStateException("Timed out waiting for SurfaceTexture frame"))
                            release(decoder, extractor)
                            return
                        }
                        if (eos && surfaceTiming != null) {
                            val finalPtsUs = lastPtsUs ?: error("No decoded video frame")
                            if (frames?.awaitLatestPresented(cancelled) != true) {
                                failPlayback(activeSessionId, VideoDecodeFailure.RENDER,
                                    SurfaceFrameSyncTimeoutException())
                                release(decoder, extractor)
                                return
                            }
                            surfaceTiming.endIteration(finalPtsUs, cancelled)
                            if (cancelled()) {
                                release(decoder, extractor)
                                return
                            }
                            loop = host.consumeVideoLoop()
                            outputDone = loop <= 0
                        }
                        if (loop > 0) {
                            host.debug("Reached EOD, looping")
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            inputDone = false
                            decoder.flush()
                            host.resetClock()
                            lastPtsUs = null
                            frameIndex = 0
                            isLoop = true
                        }
                        if (outputDone) {
                            release(decoder, extractor)
                        }
                    }
                }
            }
        }

    }

    private fun yuvProcess(
        decoder: MediaCodec,
        outputIndex: Int,
        outputBuffers: Array<out ByteBuffer>?,
        outputFormat: MediaFormat?,
        frameIndex: Int
    ) {
        val pool = checkNotNull(yuvFrames)
        val frame = pool.acquire() ?: return
        var posted = false
        try {
            frame.index = frameIndex
            frame.presentationTimeUs = bufferInfo.presentationTimeUs
            if (Build.VERSION.SDK_INT < 21 || !copyOutputImage(decoder, outputIndex, frame)) {
                val buffer = if (Build.VERSION.SDK_INT >= 21) decoder.getOutputBuffer(outputIndex)
                    else outputBuffers?.getOrNull(outputIndex)
                copyOutputBuffer(checkNotNull(buffer) { "Missing YUV output buffer" },
                    outputFormat ?: decoder.outputFormat, frame)
            }

            val target = checkNotNull(host.output)
            val id = activeSessionId
            posted = host.renderHandler?.post {
                try {
                    if (canRender(id)) {
                        host.renderYuvFrame(target, frame)
                        host.onRendering(frame.index)
                        host.swapBuffers(target)
                        pool.rendered++
                    }
                } catch (error: Throwable) {
                    failRendering(id, error)
                } finally {
                    pool.recycle(frame)
                }
            } == true
        } finally {
            if (!posted) pool.recycle(frame)
        }
    }

    @TargetApi(21)
    private fun copyOutputImage(decoder: MediaCodec, outputIndex: Int, frame: YuvFrame): Boolean {
        val image = decoder.getOutputImage(outputIndex) ?: return false
        try {
            val planes = image.planes
            val crop = image.cropRect
            require(image.format == ImageFormat.YUV_420_888 && planes.size == 3) {
                "Unsupported codec image format: ${image.format}"
            }
            require(crop.left >= 0 && crop.top >= 0 && crop.left % 2 == 0 && crop.top % 2 == 0 &&
                crop.right <= image.width && crop.bottom <= image.height &&
                crop.left.toLong() + frame.width <= crop.right &&
                crop.top.toLong() + frame.height <= crop.bottom) {
                "Unsupported codec image crop: $crop for ${frame.width}x${frame.height}"
            }
            for (index in planes.indices) {
                val plane = planes[index]
                val buffer = plane.buffer
                val chroma = index != 0
                YuvPlaneCopy.copy(buffer, buffer.position().toLong(), buffer.limit().toLong(),
                    plane.rowStride, plane.pixelStride,
                    if (chroma) crop.left / 2 else crop.left,
                    if (chroma) crop.top / 2 else crop.top,
                    if (chroma) frame.chromaWidth else frame.width,
                    if (chroma) frame.chromaHeight else frame.height,
                    when (index) {
                        0 -> frame.y
                        1 -> frame.u
                        else -> frame.v
                    })
            }
        } finally {
            image.close()
        }
        return true
    }

    private fun copyOutputBuffer(buffer: ByteBuffer, format: MediaFormat, frame: YuvFrame) {
        fun value(key: String, fallback: Int) = if (format.containsKey(key)) format.getInteger(key) else fallback
        val color = value(MediaFormat.KEY_COLOR_FORMAT, 0)
        require(color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
            color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            "Unsupported YUV buffer format: $color"
        }
        val width = value(MediaFormat.KEY_WIDTH, frame.width)
        val height = value(MediaFormat.KEY_HEIGHT, frame.height)
        val left = value("crop-left", 0)
        val top = value("crop-top", 0)
        val right = value("crop-right", width - 1)
        val bottom = value("crop-bottom", height - 1)
        require(left >= 0 && top >= 0 && right < width && bottom < height &&
            left.toLong() + frame.width - 1 <= right && top.toLong() + frame.height - 1 <= bottom) {
            "YUV frame exceeds codec output crop"
        }
        val stride = value("stride", width)
        val sliceHeight = value("slice-height", height).let { if (it == 0) height else it }
        YuvPlaneCopy.copyPacked(buffer, bufferInfo.offset, bufferInfo.size, stride, sliceHeight, left, top,
            color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, frame)
    }

    private fun release(decoder: MediaCodec?, extractor: MediaExtractor?) {
        if (!releaseQueued.compareAndSet(false, true)) return
        val id = activeSessionId
        if (setupOnDecodeThread) releaseMedia(decoder, extractor)
        host.renderHandler?.post {
            host.info("release session=$id")
            if (!setupOnDecodeThread) releaseMedia(decoder, extractor)
            safeCall("frame listener release") { glTexture?.setOnFrameAvailableListener(null) }
            safeCall("decoder texture release") { glTexture?.release() }
            glTexture = null
            surfaceFrames = null
            host.resetClock()
            host.onRelease()
            safeCall("render textures release") { host.output?.let(host::releaseTextures) }
            safeCall("codec surface release") { surface?.release() }
            surface = null
            // The TextureView is removed at completion; release EGL before handing it back.
            safeCall("render destroy") { host.output?.let(host::destroyRender) }
            host.output = null
            yuvFrames?.let { pool ->
                host.info("YUV pool slots=${pool.allocated}, rendered=${pool.rendered}, dropped=${pool.dropped}")
            }
            yuvFrames = null
            renderPending.set(false)
            drainSurfaceReleases(markIdle = true)
            host.destroyThreads(id)
            host.onPlaybackComplete(id)
        }
    }

    private fun releaseMedia(decoder: MediaCodec?, extractor: MediaExtractor?) {
        safeCall("codec stop") { decoder?.stop() }
        safeCall("codec release") { decoder?.release() }
        safeCall("extractor release") { extractor?.release() }
    }

    override fun destroy() {
        if (isRunning) isStopReq = true else destroyInner()
    }

    override fun releaseSurfaceWhenDone(release: (() -> Unit)?) {
        if (release == null) return
        synchronized(surfaceReleases) {
            if (isRunning) {
                surfaceReleases.add(release)
                return
            }
        }
        // isRunning becomes false under this same lock only after GL has been destroyed.
        // Execute directly: a detached decoder may be removing all queued thread messages.
        safeCall("release SurfaceTexture", release)
    }

    private fun drainSurfaceReleases(markIdle: Boolean = false) {
        val releases = synchronized(surfaceReleases) {
            if (markIdle) isRunning = false
            surfaceReleases.toList().also { surfaceReleases.clear() }
        }
        releases.forEach { safeCall("release SurfaceTexture", it) }
    }

    private fun destroyInner() {
        val id = host.sessionId
        val action = Runnable {
            if (isRunning || host.sessionId != id) return@Runnable
            host.onDestroy()
            safeCall("render destroy") { host.output?.let(host::destroyRender) }
            host.output = null
            drainSurfaceReleases()
            host.destroyThreads(id)
        }
        host.renderHandler?.post(action)
    }
}
