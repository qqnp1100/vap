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
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.MediaUtil
import com.tencent.qgame.vap.core.spi.VideoDecodeFailure
import com.tencent.qgame.vap.core.spi.VideoDecodeHost
import com.tencent.qgame.vap.core.spi.createVideoDecoder

class HardDecoder(player: AnimPlayer) : Decoder(player), SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "${Constant.TAG}.HardDecoder"
    }

    private val core = createVideoDecoder(object : VideoDecodeHost<IRenderListener> {
        override val sessionId get() = player.sessionId
        override val enableVersion1 get() = player.enableVersion1
        override val isOutputAvailable get() = player.isSurfaceAvailable
        override val renderHandler get() = renderThread.handler
        override val decodeHandler get() = decodeThread.handler

        override var isDecoderRunning: Boolean
            get() = isRunning
            set(value) { isRunning = value }

        override var isStopRequested: Boolean
            get() = isStopReq
            set(value) { isStopReq = value }

        override var output: IRenderListener?
            get() = render
            set(value) { render = value }

        override fun isSessionActive(id: Long) = player.isSessionActive(id)
        override fun prepareRender(needYuv: Boolean) = this@HardDecoder.prepareRender(needYuv)
        override fun preparePlay(width: Int, height: Int) = this@HardDecoder.preparePlay(width, height)
        override fun externalTexture(output: IRenderListener) = output.getExternalTexture()
        override fun clearFrame(output: IRenderListener) = output.clearFrame()

        override fun renderPackedFrame(output: IRenderListener, texture: SurfaceTexture) {
            val target = output as Render
            target.updateTextureTransform(texture)
            target.renderFrame()
        }

        override fun renderYuvFrame(output: IRenderListener, frame: YuvFrame) {
            (output as YUVRender).renderFrame(frame)
        }

        override fun swapBuffers(output: IRenderListener) = output.swapBuffers()
        override fun releaseTextures(output: IRenderListener) = output.releaseTexture()
        override fun destroyRender(output: IRenderListener) = output.destroyRender()
        override fun destroyThreads(id: Long) = destroyThread(id)
        override fun preRender(ptsUs: Long, cancelled: () -> Boolean) = speedControlUtil.preRender(ptsUs, cancelled)
        override fun resetClock() = speedControlUtil.reset()
        override fun consumeVideoLoop() = player.consumeVideoLoop(this@HardDecoder)
        override fun stopPlayback() = player.stopPlay()

        override fun onVideoStart() = this@HardDecoder.onVideoStart()

        override fun onDecoding(index: Int) {
            player.pluginManager.onDecoding(index)
            onVideoRender(index, player.configManager.config)
        }

        override fun onRendering(index: Int) = player.pluginManager.onRendering(index)
        override fun onRelease() = player.pluginManager.onRelease()
        override fun onDestroy() = player.pluginManager.onDestroy()
        override fun onPlaybackComplete(id: Long) = player.onPlaybackComplete(id, ::onVideoDestroy)
        override fun onWorkerUnavailable(id: Long) =
            player.failPreparation(id, Constant.REPORT_ERROR_TYPE_CREATE_THREAD)

        override fun onFailure(failure: VideoDecodeFailure, detail: String, cause: Throwable?) {
            val (code, message) = when (failure) {
                VideoDecodeFailure.EXTRACTOR -> Constant.REPORT_ERROR_TYPE_EXTRACTOR_EXC to Constant.ERROR_MSG_EXTRACTOR_EXC
                VideoDecodeFailure.DECODE -> Constant.REPORT_ERROR_TYPE_DECODE_EXC to Constant.ERROR_MSG_DECODE_EXC
                VideoDecodeFailure.CREATE_RENDER -> Constant.REPORT_ERROR_TYPE_CREATE_RENDER to Constant.ERROR_MSG_CREATE_RENDER
                VideoDecodeFailure.UNSUPPORTED_HEVC -> Constant.REPORT_ERROR_TYPE_HEVC_NOT_SUPPORT to Constant.ERROR_MSG_HEVC_NOT_SUPPORT
                VideoDecodeFailure.RENDER -> Constant.REPORT_ERROR_TYPE_RENDER_EXC to Constant.ERROR_MSG_RENDER_EXC
            }
            if (cause != null) ALog.e(TAG, Constant.getErrorMsg(code), cause)
            onFailed(code, "$message $detail")
        }

        override val isDebug get() = ALog.isDebug
        override fun debug(message: String) = ALog.d(TAG, message)
        override fun info(message: String) = ALog.i(TAG, message)
        override fun error(message: String, cause: Throwable) = ALog.e(TAG, message, cause)
        override fun mediaInfo(message: String) = ALog.i("AnimPlayer.MediaUtil", message)
        override fun mediaError(cause: Throwable) = ALog.e("AnimPlayer.MediaUtil", "getSupportType $cause")
        override fun cleanupError(operation: String, cause: Throwable) = ALog.e("AnimPlayer.Callback", operation, cause)
    })

    override fun start(fileContainer: IFileContainer) = core.start { MediaUtil.getExtractor(fileContainer) }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) = core.onFrameAvailable(surfaceTexture)

    override fun destroy() = core.destroy()

    override fun releaseSurfaceWhenDone(release: (() -> Unit)?) = core.releaseSurfaceWhenDone(release)
}
