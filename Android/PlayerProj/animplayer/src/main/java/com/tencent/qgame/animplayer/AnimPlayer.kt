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

import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.plugin.AnimPluginManager
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.safeCall
import com.tencent.qgame.vap.core.spi.VapEngine
import com.tencent.qgame.vap.core.spi.VapEngineHost

class AnimPlayer(val animView: IAnimView) {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimPlayer"
    }

    var animListener: IAnimListener? = null
    internal var onBeforeComplete: (() -> Unit)? = null
    var decoder: Decoder? = null
    var audioPlayer: AudioPlayer? = null
    var fps: Int = 0
        set(value) {
            decoder?.fps = value
            field = value
        }
    // 设置默认的fps <= 0 表示以vapc配置为准 > 0  表示以此设置为准
    var defaultFps: Int = 0
    var playLoop: Int
        get() = engine.playLoop
        set(value) { engine.playLoop = value }
    var supportMaskBoolean : Boolean = false
    var maskEdgeBlurBoolean : Boolean = false
    // 是否兼容老版本 默认不兼容
    var enableVersion1 : Boolean = false
    // 视频模式
    var videoMode: Int = Constant.VIDEO_MODE_SPLIT_HORIZONTAL
    @Volatile var isDetachedFromWindow = false
    @Volatile var isSurfaceAvailable = false
    @Volatile var startRunnable: Runnable? = null
    var isStartRunning: Boolean
        get() = engine.isPreparing
        set(value) { engine.isPreparing = value }
    var isMute = false // 是否静音

    val configManager = AnimConfigManager(this)
    val pluginManager = AnimPluginManager(this)

    private val engine: VapEngine<IFileContainer> = VapEngine(object : VapEngineHost<IFileContainer> {
        override fun canBegin() = !isDetachedFromWindow

        override fun prepareWorkers() = prepareDecoder()

        override fun prepareSource(source: IFileContainer, id: Long) = preparePlayback(source, id)

        override fun updateWorkerLoops(loops: Int) {
            decoder?.playLoop = loops
            audioPlayer?.playLoop = loops
        }

        override fun hasOutput() = isSurfaceAvailable

        override fun awaitOutput(start: Runnable) {
            startRunnable = start
            animView.prepareTextureView()
        }

        override fun clearOutputWait() {
            startRunnable = null
        }

        override fun startWorkers(source: IFileContainer) {
            decoder?.start(source)
            if (!isMute) audioPlayer?.start(source)
        }

        override fun stopWorkers() {
            decoder?.stop()
            stopAudio()
        }

        override fun stopAudio() {
            audioPlayer?.stop()
        }

        override fun cancelResources(id: Long) {
            pluginManager.getMixAnimPlugin()?.cancelResourceRequests(id)
        }

        override fun postPreparationCleanup(cleanup: () -> Unit) {
            val handler = decoder?.renderThread?.handler
            if (handler != null) handler.post { cleanup() } else cleanup()
        }

        override fun releasePreparedResources() = pluginManager.onRelease()

        override fun whenAudioIdle(finish: () -> Unit) {
            val audio = audioPlayer
            if (audio == null) finish() else audio.releaseWhenIdle(finish)
        }

        override fun closeSource(source: IFileContainer) = safeCall("close playback file") {
            source.close()
        }

        override fun onBeforeComplete() = safeCall("prepare completion view") {
            // Capture/remove the completed view before another session can create one.
            this@AnimPlayer.onBeforeComplete?.invoke()
        }

        override fun onSessionClosed() {
            isSurfaceAvailable = false
        }

        override fun onComplete() = safeCall("onVideoComplete") { animListener?.onVideoComplete() }

        override fun onFailure(error: Int) = safeCall("onFailed") {
            animListener?.onFailed(error, Constant.getErrorMsg(error))
        }
    })

    val sessionId: Long get() = engine.sessionId

    fun isCurrentSession(id: Long) = engine.isCurrentSession(id)

    fun isSessionActive(id: Long) = engine.isSessionActive(id)

    fun onSurfaceTextureDestroyed(releaseSurface: (() -> Unit)? = null, ownerSession: Long = sessionId) {
        if (ownerSession != sessionId) {
            releaseSurface?.let { safeCall("release old SurfaceTexture", it) }
            return
        }
        isSurfaceAvailable = false
        stopPlay()
        decoder?.destroy()
        audioPlayer?.destroy()
        // SurfaceTexture ownership stays with us until all queued GL work has ended.
        decoder?.releaseSurfaceWhenDone(releaseSurface)
            ?: releaseSurface?.let { safeCall("release SurfaceTexture", it) }
    }

    fun onSurfaceTextureAvailable(width: Int, height: Int) {
        isSurfaceAvailable = true
        decoder?.onSurfaceSizeChanged(width, height)
        val pending = startRunnable
        startRunnable = null
        pending?.run()
    }

    fun onSurfaceTextureSizeChanged(width: Int, height: Int) {
        decoder?.onSurfaceSizeChanged(width, height)
    }

    fun startPlay(fileContainer: IFileContainer) = engine.begin(fileContainer, resume = false)

    internal fun resumePlay(fileContainer: IFileContainer) = engine.begin(fileContainer, resume = true)

    private fun preparePlayback(fileContainer: IFileContainer, id: Long) {
        if (decoder?.prepareThread() != true) {
            failPreparation(id, Constant.REPORT_ERROR_TYPE_CREATE_THREAD)
            return
        }
        val posted = decoder?.renderThread?.handler?.post {
            if (!isSessionActive(id)) return@post
            val result = configManager.parseConfig(fileContainer, enableVersion1, videoMode, defaultFps)
            if (!isSessionActive(id)) return@post
            if (result != Constant.OK) {
                failPreparation(id, result)
                return@post
            }
            try {
                val config = configManager.config
                if (config == null) {
                    failPreparation(id, Constant.REPORT_ERROR_TYPE_PARSE_CONFIG)
                    return@post
                }
                val pluginResult = pluginManager.onConfigCreate(config)
                if (pluginResult != Constant.OK) {
                    failPreparation(id, pluginResult)
                    return@post
                }
                pluginManager.getMixAnimPlugin()?.fetchResources(id) { resourceResult ->
                    if (isSessionActive(id)) {
                        if (resourceResult != Constant.OK) {
                            failPreparation(id, resourceResult)
                        } else {
                            var accepted = config.isDefaultConfig
                            if (!accepted) safeCall("onVideoConfigReady") {
                                accepted = animListener?.onVideoConfigReady(config) ?: true
                            }
                            if (!isSessionActive(id)) return@fetchResources
                            if (accepted) engine.startPreparedSource(fileContainer, id)
                            else engine.finishPreparation(id)
                        }
                    }
                }
            } catch (error: Throwable) {
                ALog.e(TAG, "prepare playback", error)
                failPreparation(id, Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX)
            }
        }
        if (posted != true) failPreparation(id, Constant.REPORT_ERROR_TYPE_CREATE_THREAD)
    }

    internal fun failPreparation(id: Long, error: Int) = engine.failPreparation(id, error)

    fun stopPlay() = engine.stop()

    internal fun onPlaybackComplete(id: Long, onDestroyed: (() -> Unit)? = null) =
        engine.complete(id, onDestroyed)

    internal fun consumeVideoLoop(decoder: Decoder): Int = engine.consumeVideoLoop { --decoder.playLoop }

    fun remainingLoops(): Int = engine.remainingLoops()

    fun isRunning(): Boolean = engine.isRunning()

    private fun prepareDecoder() {
        if (decoder == null) {
            decoder = HardDecoder(this).apply {
                playLoop = this@AnimPlayer.playLoop
                fps = this@AnimPlayer.fps
            }
        }
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer(this).apply {
                playLoop = this@AnimPlayer.playLoop
            }
        }
    }

    fun updateMaskConfig(maskConfig: MaskConfig?) {
        val id = sessionId
        val bitmap = maskConfig?.alphaMaskBitmap
        val position = maskConfig?.maskPositionPair
        val texture = maskConfig?.maskTexPair
        decoder?.renderThread?.handler?.post {
            if (!isSessionActive(id)) return@post
            val config = configManager.config ?: return@post
            val current = config.maskConfig ?: MaskConfig().also { config.maskConfig = it }
            current.safeSetMaskBitmapAndReleasePre(bitmap)
            current.maskPositionPair = position
            current.maskTexPair = texture
        }
    }

}
