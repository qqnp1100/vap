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

import android.content.Context
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.tencent.qgame.animplayer.file.AssetsFileContainer
import com.tencent.qgame.animplayer.file.FileContainer
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.textureview.InnerTextureView
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.safeCall
import com.tencent.qgame.animplayer.util.IScaleType
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.animplayer.util.ScaleTypeUtil
import java.io.File
import java.util.concurrent.atomic.AtomicLong

open class AnimView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0):
    IAnimView,
    FrameLayout(context, attrs, defStyleAttr),
    TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimView"
    }
    private lateinit var player: AnimPlayer

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var surface: SurfaceTexture? = null
    private var surfaceSession = 0L
    private val playRequest = AtomicLong()
    @Volatile private var resumeWhenStopped = false
    private var animListener: IAnimListener? = null
    @Volatile private var innerTextureView: InnerTextureView? = null
    private var lastFile: IFileContainer? = null
    private val scaleTypeUtil = ScaleTypeUtil()

    // 代理监听
    private val animProxyListener by lazy {
        object : IAnimListener {

            override fun onVideoConfigReady(config: AnimConfig): Boolean {
                scaleTypeUtil.setVideoSize(config.width, config.height)
                return animListener?.onVideoConfigReady(config) ?: super.onVideoConfigReady(config)
            }

            override fun onVideoStart() {
                animListener?.onVideoStart()
            }

            override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
                animListener?.onVideoRender(frameIndex, config)
            }

            override fun onVideoComplete() {
                if (resumeWhenStopped) ui {
                    if (resumeWhenStopped && !player.isDetachedFromWindow) {
                        resumeWhenStopped = false
                        lastFile?.let { startPlayInternal(it, true) }
                    }
                }
                animListener?.onVideoComplete()
            }

            override fun onVideoDestroy() {
                animListener?.onVideoDestroy()
            }

            override fun onFailed(errorType: Int, errorMsg: String?) {
                animListener?.onFailed(errorType, errorMsg)
            }

        }
    }

    // 保证AnimView已经布局完成才加入TextureView
    private var onSizeChangedCalled = false
    private var needPrepareTextureView = false
    private var preparingSession = 0L
    private val prepareTextureViewRunnable = Runnable {
        if (!player.isSessionActive(preparingSession) || player.isDetachedFromWindow) return@Runnable
        innerTextureView?.let { removeView(it) }
        val id = preparingSession
        val textureView = InnerTextureView(context).apply {
            player = this@AnimView.player
            isOpaque = false
            layoutParams = scaleTypeUtil.getLayoutParam(this)
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (innerTextureView !== textureView || !player.isSessionActive(id)) {
                    removeView(textureView)
                    return
                }
                this@AnimView.surface = surface
                surfaceSession = id
                player.onSurfaceTextureAvailable(width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
                destroySurface(textureView, id, surface)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                if (player.isSessionActive(id)) player.onSurfaceTextureSizeChanged(width, height)
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        innerTextureView = textureView
        addView(textureView)
    }


    init {
        hide()
        player = AnimPlayer(this)
        player.onBeforeComplete = { hide() }
        player.animListener = animProxyListener
    }


    override fun prepareTextureView() {
        val id = player.sessionId
        ui {
            if (!player.isSessionActive(id) || player.isDetachedFromWindow) return@ui
            preparingSession = id
            if (onSizeChangedCalled) {
                uiHandler.removeCallbacks(prepareTextureViewRunnable)
                uiHandler.post(prepareTextureViewRunnable)
            } else {
                needPrepareTextureView = true
            }
        }
    }

    override fun getSurfaceTexture(): SurfaceTexture? {
        return innerTextureView?.surfaceTexture ?: surface
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.i(TAG, "onSurfaceTextureSizeChanged $width x $height")
        player.onSurfaceTextureSizeChanged(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
        destroySurface(innerTextureView.takeIf { this.surface === surface }, surfaceSession, surface)

    private fun destroySurface(oldView: InnerTextureView?, ownerSession: Long, surface: SurfaceTexture): Boolean {
        if (this.surface === surface) this.surface = null
        player.onSurfaceTextureDestroyed({ safeCall("release view surface") { surface.release() } }, ownerSession)
        uiHandler.post {
            oldView?.surfaceTextureListener = null
            oldView?.let { removeView(it) }
            if (innerTextureView === oldView) innerTextureView = null
        }
        // We own the SurfaceTexture until render cleanup has completed.
        return false
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ALog.i(TAG, "onSurfaceTextureAvailable width=$width height=$height")
        this.surface = surface
        surfaceSession = preparingSession
        player.onSurfaceTextureAvailable(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ALog.i(TAG, "onSizeChanged w=$w, h=$h")
        scaleTypeUtil.setLayoutSize(w, h)
        onSizeChangedCalled = true
        // 需要保证onSizeChanged被调用
        if (needPrepareTextureView) {
            needPrepareTextureView = false
            prepareTextureView()
        }
    }

    override fun onAttachedToWindow() {
        ALog.i(TAG, "onAttachedToWindow")
        super.onAttachedToWindow()
        player.isDetachedFromWindow = false
        // 自动恢复播放
        if (player.remainingLoops() > 0) {
            if (player.isRunning()) resumeWhenStopped = true
            else lastFile?.let { startPlayInternal(it, true) }
        }
    }

    override fun onDetachedFromWindow() {
        ALog.i(TAG, "onDetachedFromWindow")
        super.onDetachedFromWindow()
        player.isDetachedFromWindow = true
        playRequest.incrementAndGet()
        uiHandler.removeCallbacks(prepareTextureViewRunnable)
        needPrepareTextureView = false
        player.onSurfaceTextureDestroyed()
    }


    override fun setAnimListener(animListener: IAnimListener?) {
        this.animListener = animListener
    }

    override fun setFetchResource(fetchResource: IFetchResource?) {
        player.pluginManager.getMixAnimPlugin()?.resourceRequest = fetchResource
    }

    override fun setOnResourceClickListener(resourceClickListener: OnResourceClickListener?) {
        player.pluginManager.getMixAnimPlugin()?.resourceClickListener = resourceClickListener
    }

    /**
     * 兼容方案，优先保证表情显示
     */
    open fun enableAutoTxtColorFill(enable: Boolean) {
        player.pluginManager.getMixAnimPlugin()?.autoTxtColorFill = enable
    }

    override fun setLoop(playLoop: Int) {
        player.playLoop = playLoop
    }

    override fun supportMask(isSupport : Boolean, isEdgeBlur : Boolean) {
        player.supportMaskBoolean = isSupport
        player.maskEdgeBlurBoolean = isEdgeBlur
    }

    override fun updateMaskConfig(maskConfig: MaskConfig?) {
        player.updateMaskConfig(maskConfig)
    }


    @Deprecated("Compatible older version mp4, default false")
    fun enableVersion1(enable: Boolean) {
        player.enableVersion1 = enable
    }

    // 兼容老版本视频模式
    @Deprecated("Compatible older version mp4")
    fun setVideoMode(mode: Int) {
        player.videoMode = mode
    }

    override fun setFps(fps: Int) {
        ALog.i(TAG, "setFps=$fps")
        player.defaultFps = fps
    }

    override fun setScaleType(type : ScaleType) {
        scaleTypeUtil.currentScaleType = type
    }

    override fun setScaleType(scaleType: IScaleType) {
        scaleTypeUtil.scaleTypeImpl = scaleType
    }

    /**
     * @param isMute true 静音
     */
    override fun setMute(isMute: Boolean) {
        ALog.i(TAG, "set mute=$isMute")
        player.isMute = isMute
    }

    override fun startPlay(file: File) {
        try {
            val fileContainer = FileContainer(file)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            safeCall("onFailed") { animProxyListener.onFailed(Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR) }
            safeCall("onVideoComplete") { animProxyListener.onVideoComplete() }
        }
    }

    override fun startPlay(assetManager: AssetManager, assetsPath: String) {
        try {
            val fileContainer = AssetsFileContainer(assetManager, assetsPath)
            startPlay(fileContainer)
        } catch (e: Throwable) {
            safeCall("onFailed") { animProxyListener.onFailed(Constant.REPORT_ERROR_TYPE_FILE_ERROR, Constant.ERROR_MSG_FILE_ERROR) }
            safeCall("onVideoComplete") { animProxyListener.onVideoComplete() }
        }
    }


    override fun startPlay(fileContainer: IFileContainer) = startPlayInternal(fileContainer, false)

    private fun startPlayInternal(fileContainer: IFileContainer, resume: Boolean) {
        val request = playRequest.incrementAndGet()
        ui {
            if (playRequest.get() != request || player.isDetachedFromWindow) return@ui
            if (visibility != View.VISIBLE) {
                ALog.e(TAG, "AnimView is GONE, can't play")
                return@ui
            }
            if (!player.isRunning()) {
                lastFile = fileContainer
                if (resume) player.resumePlay(fileContainer) else player.startPlay(fileContainer)
            } else {
                ALog.e(TAG, "is running can not start")
            }
        }
    }


    override fun stopPlay() {
        val request = playRequest.incrementAndGet()
        resumeWhenStopped = false
        uiHandler.removeCallbacks(prepareTextureViewRunnable)
        player.stopPlay()
        ui { if (playRequest.get() == request) needPrepareTextureView = false }
    }

    override fun isRunning(): Boolean {
        return player.isRunning()
    }

    override fun getRealSize(): Pair<Int, Int> {
        return scaleTypeUtil.getRealSize()
    }

    internal fun estimateVideoSize(videoWidth: Int, videoHeight: Int): Pair<Int, Int>? =
        scaleTypeUtil.estimateVideoSize(videoWidth, videoHeight)

    private fun hide() {
        val oldView = innerTextureView
        ui {
            oldView?.let { removeView(it) }
            if (innerTextureView === oldView) innerTextureView = null
        }
    }

    private fun ui(f:()->Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) f() else uiHandler.post { f() }
    }

}
