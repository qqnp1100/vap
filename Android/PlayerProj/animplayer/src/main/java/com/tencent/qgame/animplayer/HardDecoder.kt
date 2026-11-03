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
package com.tencent.qgame.animplayer

import android.annotation.TargetApi
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.MediaUtil
import com.tencent.qgame.animplayer.util.safeCall
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class HardDecoder(player: AnimPlayer) : Decoder(player), SurfaceTexture.OnFrameAvailableListener {


    companion object {
        private const val TAG = "${Constant.TAG}.HardDecoder"
    }

    private var surface: Surface? = null
    private var glTexture: SurfaceTexture? = null
    private val bufferInfo by lazy { MediaCodec.BufferInfo() }
    private var activeSessionId = 0L
    private val releaseQueued = AtomicBoolean()
    private val renderPending = AtomicBoolean()
    private val surfaceReleases = ArrayList<() -> Unit>()

    // 动画的原始尺寸
    private var videoWidth = 0
    private var videoHeight = 0

    private var needYUV = false
    private var yuvFrames: YuvFramePool? = null


    override fun start(fileContainer: IFileContainer) {
        activeSessionId = player.sessionId
        releaseQueued.set(false)
        isStopReq = false
        isRunning = true
        val posted = renderThread.handler?.post {
            if (player.isSessionActive(activeSessionId)) startPlay(fileContainer)
            else release(null, null)
        }
        if (posted != true) {
            isRunning = false
            player.failPreparation(activeSessionId, Constant.REPORT_ERROR_TYPE_CREATE_THREAD)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (isStopReq || surfaceTexture !== glTexture || !player.isSessionActive(activeSessionId)) return
        ALog.d(TAG, "onFrameAvailable")
        renderData()
    }

    private fun renderData() {
        val id = activeSessionId
        if (!renderPending.compareAndSet(false, true)) return
        val posted = renderThread.handler?.post {
            try {
                if (!isStopReq && player.isSessionActive(id) && player.isSurfaceAvailable) {
                    glTexture?.apply {
                        updateTexImage()
                        render?.renderFrame()
                        player.pluginManager.onRendering()
                        render?.swapBuffers()
                    }
                }
            } catch (e: Throwable) {
                ALog.e(TAG, "render exception", e)
            } finally {
                renderPending.set(false)
            }
        }
        if (posted != true) renderPending.set(false)
    }

    private fun startPlay(fileContainer: IFileContainer) {

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var format: MediaFormat? = null
        var trackIndex = 0

        try {
            extractor = MediaUtil.getExtractor(fileContainer)
            trackIndex = MediaUtil.selectVideoTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found")
            }
            extractor.selectTrack(trackIndex)
            format = extractor.getTrackFormat(trackIndex)
            if (format == null) throw RuntimeException("format is null")

            // 是否支持h265
            if (MediaUtil.checkIsHevc(format)) {
                if (Build.VERSION.SDK_INT  < Build.VERSION_CODES.LOLLIPOP
                    || !MediaUtil.checkSupportCodec(MediaUtil.MIME_HEVC)) {

                    onFailed(Constant.REPORT_ERROR_TYPE_HEVC_NOT_SUPPORT,
                        "${Constant.ERROR_MSG_HEVC_NOT_SUPPORT} " +
                                "sdk:${Build.VERSION.SDK_INT}" +
                                ",support hevc:" + MediaUtil.checkSupportCodec(MediaUtil.MIME_HEVC))
                    release(null, extractor)
                    return
                }
            }

            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            ALog.i(TAG, "Video size is $videoWidth x $videoHeight")

            // 由于使用mediacodec解码老版本素材时对宽度1500尺寸的视频进行数据对齐，解码后的宽度变成1504，导致采样点出现偏差播放异常
            // 所以当开启兼容老版本视频模式并且老版本视频的宽度不能被16整除时要走YUV渲染逻辑
            // 但是这样直接判断有风险，后期想办法改
            needYUV = videoWidth % 16 != 0 && player.enableVersion1
            yuvFrames = if (needYUV) YuvFramePool(videoWidth, videoHeight) else null

            try {
                if (!prepareRender(needYUV)) {
                    throw RuntimeException("render create fail")
                }
            } catch (t: Throwable) {
                onFailed(Constant.REPORT_ERROR_TYPE_CREATE_RENDER, "${Constant.ERROR_MSG_CREATE_RENDER} e=$t")
                release(null, extractor)
                return
            }

            preparePlay(videoWidth, videoHeight)

            render?.apply {
                if (!needYUV) {
                    glTexture = SurfaceTexture(getExternalTexture()).apply {
                        setOnFrameAvailableListener(this@HardDecoder)
                        setDefaultBufferSize(videoWidth, videoHeight)
                    }
                }
                clearFrame()
            }

        } catch (e: Throwable) {
            ALog.e(TAG, "MediaExtractor exception e=$e", e)
            onFailed(Constant.REPORT_ERROR_TYPE_EXTRACTOR_EXC, "${Constant.ERROR_MSG_EXTRACTOR_EXC} e=$e")
            release(decoder, extractor)
            return
        }

        try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            ALog.i(TAG, "Video MIME is $mime")
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec // Own it before configure/start, either of which may throw.
            if (needYUV) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                codec.configure(format, null, null, 0)
            } else {
                surface = Surface(glTexture)
                codec.configure(format, surface, null, 0)
            }
            codec.start()
            if (decodeThread.handler?.post {
                try {
                    startDecode(extractor, codec)
                } catch (e: Throwable) {
                    ALog.e(TAG, "MediaCodec exception", e)
                    onFailed(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "${Constant.ERROR_MSG_DECODE_EXC} e=$e")
                    release(codec, extractor)
                }
            } != true) release(codec, extractor)
        } catch (e: Throwable) {
            ALog.e(TAG, "MediaCodec configure exception e=$e", e)
            onFailed(Constant.REPORT_ERROR_TYPE_DECODE_EXC, "${Constant.ERROR_MSG_DECODE_EXC} e=$e")
            release(decoder, extractor)
            return
        }
    }

    private fun startDecode(extractor: MediaExtractor ,decoder: MediaCodec) {
        val TIMEOUT_USEC = 10000L
        var inputChunk = 0
        var outputDone = false
        var inputDone = false
        var frameIndex = 0
        var isLoop = false

        val decoderInputBuffers = decoder.inputBuffers
        var outputBuffers = if (needYUV && Build.VERSION.SDK_INT < 21) decoder.outputBuffers else null
        var outputFormat: MediaFormat? = null

        while (!outputDone) {
            if (isStopReq || !player.isSessionActive(activeSessionId)) {
                ALog.i(TAG, "stop decode")
                release(decoder, extractor)
                return
            }

            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufIndex >= 0) {
                    val inputBuf = decoderInputBuffers[inputBufIndex]
                    val chunkSize = extractor.readSampleData(inputBuf, 0)
                    if (chunkSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        ALog.d(TAG, "decode EOS")
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0)
                        ALog.d(TAG, "submitted frame $inputChunk to dec, size=$chunkSize")
                        inputChunk++
                        extractor.advance()
                    }
                } else {
                    ALog.d(TAG, "input buffer not available")
                }
            }

            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                when {
                    decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> ALog.d(TAG, "no output from decoder available")
                    decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        if (needYUV && Build.VERSION.SDK_INT < 21) outputBuffers = decoder.outputBuffers
                    }
                    decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        ALog.i(TAG, "decoder output format changed: $outputFormat")
                    }
                    decoderStatus < 0 -> {
                        throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                    }
                    else -> {
                        var loop = 0
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            loop = player.consumeVideoLoop(this)
                            outputDone = loop <= 0
                        }
                        val doRender = if (needYUV) {
                            bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        } else !outputDone
                        if (doRender) {
                            speedControlUtil.preRender(bufferInfo.presentationTimeUs) { isStopReq || !player.isSessionActive(activeSessionId) }
                            if (isStopReq || !player.isSessionActive(activeSessionId)) {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                                release(decoder, extractor)
                                return
                            }
                        }

                        if (needYUV) {
                            try {
                                if (doRender) yuvProcess(decoder, decoderStatus, outputBuffers, outputFormat, frameIndex)
                            } finally {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                            }
                        } else {
                            decoder.releaseOutputBuffer(decoderStatus, doRender)
                        }

                        if (!needYUV || doRender) {
                            if (frameIndex == 0 && !isLoop) onVideoStart()
                            player.pluginManager.onDecoding(frameIndex)
                            onVideoRender(frameIndex, player.configManager.config)
                            frameIndex++
                        }
                        if (loop > 0) {
                            ALog.d(TAG, "Reached EOD, looping")
                            player.pluginManager.onLoopStart()
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            inputDone = false
                            decoder.flush()
                            speedControlUtil.reset()
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

            val target = render as YUVRender
            val id = activeSessionId
            posted = renderThread.handler?.post {
                try {
                    if (!isStopReq && player.isSessionActive(id) && player.isSurfaceAvailable) {
                        target.renderFrame(frame)
                        player.pluginManager.onRendering(frame.index)
                        target.swapBuffers()
                        pool.rendered++
                    }
                } catch (error: Throwable) {
                    ALog.e(TAG, "YUV render exception frame=${frame.index}, ptsUs=${frame.presentationTimeUs}", error)
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
        renderThread.handler?.post {
            ALog.i(TAG, "release session=$id")
            safeCall("codec stop") { decoder?.stop() }
            safeCall("codec release") { decoder?.release() }
            safeCall("extractor release") { extractor?.release() }
            safeCall("frame listener release") { glTexture?.setOnFrameAvailableListener(null) }
            safeCall("decoder texture release") { glTexture?.release() }
            glTexture = null
            speedControlUtil.reset()
            player.pluginManager.onRelease()
            safeCall("render textures release") { render?.releaseTexture() }
            safeCall("codec surface release") { surface?.release() }
            surface = null
            // The TextureView is removed at completion; release EGL before handing it back.
            safeCall("render destroy") { render?.destroyRender() }
            render = null
            yuvFrames?.let { pool ->
                ALog.i(TAG, "YUV pool slots=${pool.allocated}, rendered=${pool.rendered}, dropped=${pool.dropped}")
            }
            yuvFrames = null
            renderPending.set(false)
            drainSurfaceReleases(markIdle = true)
            destroyThread(id)
            player.onPlaybackComplete(id, ::onVideoDestroy)
        }
    }

    override fun destroy() {
        if (isRunning) stop() else destroyInner()
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
        val id = player.sessionId
        val action = Runnable {
            if (isRunning || player.sessionId != id) return@Runnable
            player.pluginManager.onDestroy()
            safeCall("render destroy") { render?.destroyRender() }
            render = null
            drainSurfaceReleases()
            destroyThread(id)
        }
        renderThread.handler?.post(action)
    }
}
