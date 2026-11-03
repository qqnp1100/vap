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

    // 动画对齐后的尺寸
    private var alignWidth = 0
    private var alignHeight = 0

    // 动画是否需要走YUV渲染逻辑的标志位
    private var needYUV = false
    private var outputFormat: MediaFormat? = null

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
            // 防止没有INFO_OUTPUT_FORMAT_CHANGED时导致alignWidth和alignHeight不会被赋值一直是0
            alignWidth = videoWidth
            alignHeight = videoHeight
            ALog.i(TAG, "Video size is $videoWidth x $videoHeight")

            // 由于使用mediacodec解码老版本素材时对宽度1500尺寸的视频进行数据对齐，解码后的宽度变成1504，导致采样点出现偏差播放异常
            // 所以当开启兼容老版本视频模式并且老版本视频的宽度不能被16整除时要走YUV渲染逻辑
            // 但是这样直接判断有风险，后期想办法改
            needYUV = videoWidth % 16 != 0 && player.enableVersion1

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
                glTexture = SurfaceTexture(getExternalTexture()).apply {
                    setOnFrameAvailableListener(this@HardDecoder)
                    setDefaultBufferSize(videoWidth, videoHeight)
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
                    decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> ALog.d(TAG, "decoder output buffers changed")
                    decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                        outputFormat?.apply {
                            try {
                                // 有可能取到空值，做一层保护
                                val stride = getInteger("stride")
                                val sliceHeight = getInteger("slice-height")
                                if (stride > 0 && sliceHeight > 0) {
                                    alignWidth = stride
                                    alignHeight = sliceHeight
                                }
                            } catch (t: Throwable) {
                                ALog.e(TAG, "$t", t)
                            }
                        }
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
                        val doRender = !outputDone
                        if (doRender) {
                            speedControlUtil.preRender(bufferInfo.presentationTimeUs) { isStopReq || !player.isSessionActive(activeSessionId) }
                            if (isStopReq || !player.isSessionActive(activeSessionId)) {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                                release(decoder, extractor)
                                return
                            }
                        }

                        if (needYUV && doRender) {
                            yuvProcess(decoder, decoderStatus)
                        }

                        // release & render
                        decoder.releaseOutputBuffer(decoderStatus, doRender && !needYUV)

                        if (frameIndex == 0 && !isLoop) {
                            onVideoStart()
                        }
                        player.pluginManager.onDecoding(frameIndex)
                        onVideoRender(frameIndex, player.configManager.config)

                        frameIndex++
                        ALog.d(TAG, "decode frameIndex=$frameIndex")
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

    /**
     * 获取到解码后每一帧的YUV数据，裁剪出正确的尺寸
     */
    private fun yuvProcess(decoder: MediaCodec, outputIndex: Int) {
        val outputBuffer = decoder.outputBuffers[outputIndex]
        outputBuffer?.let {
            it.position(0)
            it.limit(bufferInfo.offset + bufferInfo.size)
            var yuvData = ByteArray(outputBuffer.remaining())
            outputBuffer.get(yuvData)

            if (yuvData.isNotEmpty()) {
                var yData = ByteArray(videoWidth * videoHeight)
                var uData = ByteArray(videoWidth * videoHeight / 4)
                var vData = ByteArray(videoWidth * videoHeight / 4)

                if (outputFormat?.getInteger(MediaFormat.KEY_COLOR_FORMAT) == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    yuvData = yuv420spTop(yuvData)
                }

                yuvCopy(yuvData, 0, alignWidth, alignHeight, yData, videoWidth, videoHeight)
                yuvCopy(yuvData, alignWidth * alignHeight, alignWidth / 2, alignHeight / 2, uData, videoWidth / 2, videoHeight / 2)
                yuvCopy(yuvData, alignWidth * alignHeight * 5 / 4, alignWidth / 2, alignHeight / 2, vData, videoWidth / 2, videoHeight / 2)

                render?.setYUVData(videoWidth, videoHeight, yData, uData, vData)
                renderData()
            }
        }
    }

    private fun yuv420spTop(yuv420sp: ByteArray): ByteArray {
        val yuv420p = ByteArray(yuv420sp.size)
        val ySize = alignWidth * alignHeight
        System.arraycopy(yuv420sp, 0, yuv420p, 0, alignWidth * alignHeight)
        var i = ySize
        var j = ySize
        while (i < ySize * 3 / 2) {
            yuv420p[j] = yuv420sp[i]
            yuv420p[j + ySize / 4] = yuv420sp[i + 1]
            i += 2
            j++
        }
        return yuv420p
    }

    private fun yuvCopy(src: ByteArray, srcOffset: Int, inWidth: Int, inHeight: Int, dest: ByteArray, outWidth: Int, outHeight: Int) {
        for (h in 0 until inHeight) {
            if (h < outHeight) {
                System.arraycopy(src, srcOffset + h * inWidth, dest, h * outWidth, outWidth)
            }
        }
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
