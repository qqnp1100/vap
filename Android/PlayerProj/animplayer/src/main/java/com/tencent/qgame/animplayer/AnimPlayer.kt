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
    var playLoop: Int = 0
        set(value) {
            synchronized(sessionLock) {
                field = value
                remainingVideoLoops = value
                decoder?.playLoop = value
                audioPlayer?.playLoop = value
            }
        }
    var supportMaskBoolean : Boolean = false
    var maskEdgeBlurBoolean : Boolean = false
    // 是否兼容老版本 默认不兼容
    var enableVersion1 : Boolean = false
    // 视频模式
    var videoMode: Int = Constant.VIDEO_MODE_SPLIT_HORIZONTAL
    @Volatile var isDetachedFromWindow = false
    @Volatile var isSurfaceAvailable = false
    @Volatile var startRunnable: Runnable? = null
    @Volatile var isStartRunning = false // 启动时运行状态
    var isMute = false // 是否静音

    val configManager = AnimConfigManager(this)
    val pluginManager = AnimPluginManager(this)

    private val sessionLock = Any()
    @Volatile var sessionId = 0L
        private set
    @Volatile private var sessionOpen = false
    @Volatile private var cancelled = false
    private var currentFile: IFileContainer? = null
    private var completionPending = false

    fun isCurrentSession(id: Long) = sessionOpen && sessionId == id
    fun isSessionActive(id: Long) = isCurrentSession(id) && !cancelled

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

    fun startPlay(fileContainer: IFileContainer) = beginPlay(fileContainer, resume = false)

    internal fun resumePlay(fileContainer: IFileContainer) = beginPlay(fileContainer, resume = true)

    private fun beginPlay(fileContainer: IFileContainer, resume: Boolean) {
        val id: Long
        synchronized(sessionLock) {
            if (sessionOpen || isDetachedFromWindow) return
            sessionId++
            id = sessionId
            sessionOpen = true
            completionPending = false
            cancelled = false
            isStartRunning = true
            currentFile = fileContainer
            if (!resume) remainingVideoLoops = playLoop
            prepareDecoder()
        }
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
                            if (accepted) innerStartPlay(fileContainer, id)
                            else finishPreparation(id)
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

    internal fun failPreparation(id: Long, error: Int) {
        if (!isCurrentSession(id)) return
        safeCall("onFailed") { animListener?.onFailed(error, Constant.getErrorMsg(error)) }
        finishPreparation(id)
    }

    private fun finishPreparation(id: Long) {
        if (!isCurrentSession(id)) return
        pluginManager.onRelease()
        onPlaybackComplete(id)
    }

    private fun innerStartPlay(fileContainer: IFileContainer, id: Long) {
        synchronized(sessionLock) {
            if (!isSessionActive(id)) return
            if (isSurfaceAvailable) {
                isStartRunning = false
                decoder?.playLoop = remainingVideoLoops
                audioPlayer?.playLoop = remainingVideoLoops
                decoder?.start(fileContainer)
                if (!isMute) audioPlayer?.start(fileContainer)
            } else {
                startRunnable = Runnable { innerStartPlay(fileContainer, id) }
                animView.prepareTextureView()
            }
        }
    }

    fun stopPlay() {
        val id: Long
        val preparing: Boolean
        synchronized(sessionLock) {
            id = sessionId
            cancelled = true
            preparing = sessionOpen && isStartRunning
            startRunnable = null
            decoder?.stop()
            audioPlayer?.stop()
        }
        pluginManager.getMixAnimPlugin()?.cancelResourceRequests(id)
        if (preparing) {
            val handler = decoder?.renderThread?.handler
            if (handler != null) handler.post { finishPreparation(id) }
            else finishPreparation(id)
        }
    }

    internal fun onPlaybackComplete(id: Long, onDestroyed: (() -> Unit)? = null) {
        synchronized(sessionLock) {
            if (!isCurrentSession(id) || completionPending) return
            completionPending = true
            cancelled = true
            startRunnable = null
            audioPlayer?.stop()
        }
        val finish: () -> Unit = {
            synchronized(sessionLock) {
                if (isCurrentSession(id)) {
                    safeCall("close playback file") { currentFile?.close() }
                    currentFile = null
                    // Capture/remove the completed view before another session can create one.
                    safeCall("prepare completion view") { onBeforeComplete?.invoke() }
                    isStartRunning = false
                    isSurfaceAvailable = false
                    sessionOpen = false
                }
            }
            safeCall("onVideoComplete") { animListener?.onVideoComplete() }
            onDestroyed?.invoke()
        }
        // Custom file sources may still be read by audio after video reaches EOS.
        val audio = audioPlayer
        if (audio == null) finish() else audio.releaseWhenIdle(finish)
    }

    internal fun consumeVideoLoop(decoder: Decoder): Int = synchronized(sessionLock) {
        val remaining = --decoder.playLoop
        remainingVideoLoops = remaining
        remaining
    }
    @Volatile private var remainingVideoLoops = 0

    fun remainingLoops(): Int = remainingVideoLoops

    fun isRunning(): Boolean = sessionOpen

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
