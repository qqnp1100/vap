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
package com.tencent.qgame.animplayer.mix

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimPlayer
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.plugin.IAnimPlugin
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.BitmapUtil
import com.tencent.qgame.animplayer.util.safeCall
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MixAnimPlugin(val player: AnimPlayer): IAnimPlugin {

    companion object {
        private const val TAG = "${Constant.TAG}.MixAnimPlugin"
    }
    var resourceRequest: IFetchResource? = null
    private var resourceOwner: IFetchResource? = null
    private val imagePlaceholders = HashSet<Src>()
    var resourceClickListener: OnResourceClickListener? = null
    var srcMap: SrcMap? = null
    var frameAll: FrameAll? = null
    var curFrameIndex = -1 // 当前帧
    private var mixRender:MixRender? = null
    private val mixTouch by lazy { MixTouch(this) }
    var autoTxtColorFill = true // 是否启动自动文字填充 默认开启

    private val loadGeneration = AtomicLong()
    @Volatile private var timeoutTask: Runnable? = null
    private val pendingImageLock = Any()
    private var resourceSession = 0L
    private val pendingImages = HashSet<() -> Unit>()
    /** Upper bound for application resource callbacks, including callbacks that never arrive. */
    var resourceTimeoutMs = 10_000L

    override fun onConfigCreate(config: AnimConfig): Int {
        if (!config.isMix || resourceRequest == null) return Constant.OK
        resourceOwner = resourceRequest
        parseSrc(config)
        parseFrame(config)
        return Constant.OK
    }

    /** Gather on the render handler without blocking it, so stop/destroy can always run. */
    fun fetchResources(session: Long, complete: (Int) -> Unit) {
        val request = resourceOwner
        val sources = srcMap?.map?.values?.toList().orEmpty()
        if (request == null || sources.isEmpty()) {
            complete(Constant.OK)
            return
        }
        val handler = player.decoder?.renderThread?.handler ?: return
        val generation = synchronized(pendingImageLock) {
            resourceSession = session
            loadGeneration.incrementAndGet()
        }
        var remaining = sources.size
        var finished = false
        fun current() = generation == loadGeneration.get() && player.isSessionActive(session)
        fun finish(result: Int) {
            if (finished || !current()) return
            finished = true
            timeoutTask?.let { handler.removeCallbacks(it) }
            timeoutTask = null
            // Invalidate callbacks arriving after failure/timeout.
            if (result != Constant.OK) cancelResourceRequests()
            complete(result)
        }
        fun received(valid: Boolean) {
            if (!current() || finished) return
            if (!valid) {
                finish(Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX)
                return
            }
            remaining--
            if (remaining == 0) {
                val validBitmaps = createBitmap() && sources.all {
                    it.bitmap != null && it.bitmap?.isRecycled == false && it.bitmap?.config != Bitmap.Config.ALPHA_8
                }
                finish(if (validBitmaps) Constant.OK else Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX)
            }
        }
        timeoutTask = Runnable { finish(Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX) }.also {
            handler.postDelayed(it, resourceTimeoutMs.coerceAtLeast(1L))
        }
        sources.forEach { src ->
            if (!current() || finished) return@forEach
            val once = AtomicBoolean()
            val seenBitmaps = java.util.IdentityHashMap<Bitmap, Boolean>()
            try {
                when (src.srcType) {
                    Src.SrcType.IMG -> request.fetchImage(Resource(src)) { bitmap ->
                        // A duplicate carrying the same object must never recycle an accepted image.
                        val firstObject = bitmap == null || synchronized(seenBitmaps) {
                            seenBitmaps.put(bitmap, true) == null
                        }
                        if (!firstObject) return@fetchImage
                        val first = once.compareAndSet(false, true)
                        fun releaseUnused() {
                            if (bitmap != null) safeCall("release late image") {
                                request.releaseResource(listOf(Resource(src).apply { this.bitmap = bitmap }))
                            }
                        }
                        val delivered = AtomicBoolean()
                        lateinit var discard: () -> Unit
                        discard = {
                            if (delivered.compareAndSet(false, true)) {
                                synchronized(pendingImageLock) { pendingImages.remove(discard) }
                                releaseUnused()
                            }
                        }
                        val enqueue = synchronized(pendingImageLock) {
                            if (first && current()) {
                                pendingImages.add(discard)
                                true
                            } else false
                        }
                        if (!enqueue) {
                            discard()
                        } else if (!handler.post {
                            if (delivered.compareAndSet(false, true)) {
                                synchronized(pendingImageLock) { pendingImages.remove(discard) }
                                if (!current() || finished) {
                                    releaseUnused()
                                } else {
                                    src.bitmap = bitmap
                                    received(bitmap == null || !bitmap.isRecycled)
                                }
                            }
                        }) {
                            discard()
                        }
                    }
                    Src.SrcType.TXT -> request.fetchText(Resource(src)) { text ->
                        if (once.compareAndSet(false, true)) handler.post {
                            if (current() && !finished) {
                                src.txt = text ?: ""
                                received(true)
                            }
                        }
                    }
                    else -> received(false)
                }
            } catch (error: Throwable) {
                ALog.e(TAG, "fetch resource ${src.srcId}", error)
                finish(Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX)
            }
        }
    }

    fun cancelResourceRequests(expectedSession: Long? = null) {
        val timeout: Runnable?
        val pending: List<() -> Unit>
        synchronized(pendingImageLock) {
            if (expectedSession != null && resourceSession != expectedSession) return
            loadGeneration.incrementAndGet()
            timeout = timeoutTask
            timeoutTask = null
            pending = pendingImages.toList()
            pendingImages.clear()
        }
        timeout?.let { player.decoder?.renderThread?.handler?.removeCallbacks(it) }
        pending.forEach { it() }
    }

    override fun onRenderCreate() {
        if (player.configManager.config?.isMix == false) return
        ALog.i(TAG, "mix render init")
        mixRender = MixRender(this)
        mixRender?.init()
    }

    override fun onRendering(frameIndex: Int) {
        val config = player.configManager.config ?: return
        if (!config.isMix) return
        curFrameIndex = frameIndex
        val list = frameAll?.map?.get(frameIndex)?.list ?: return
        list.forEach {frame ->
            val src = srcMap?.map?.get(frame.srcId) ?: return@forEach
            mixRender?.renderFrame(config, frame, src)
        }
    }


    override fun onRelease() {
        destroy()
    }

    override fun onDestroy() {
        destroy()
    }

    override fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        if (player.configManager.config?.isMix == false || resourceClickListener == null) {
            return super.onDispatchTouchEvent(ev)
        }
        mixTouch.onTouchEvent(ev)?.let {resource ->
            Handler(Looper.getMainLooper()).post {
                safeCall("onResourceClick") { resourceClickListener?.onClick(resource) }
            }
        }
        // 只要注册监听则拦截所有事件
        return true
    }

    private fun destroy() {
        cancelResourceRequests()
        val sources = srcMap?.map?.values?.toList().orEmpty()
        val renderer = mixRender
        val owner = resourceOwner
        resourceOwner = null
        srcMap = null
        frameAll = null
        mixRender = null
        curFrameIndex = -1
        val resources = ArrayList<Resource>()
        sources.forEach { src ->
            safeCall("release mix texture") { renderer?.release(src.srcTextureId) }
            src.srcTextureId = 0
            when (src.srcType) {
                Src.SrcType.IMG -> if (imagePlaceholders.remove(src)) {
                    safeCall("release placeholder bitmap") { src.bitmap?.recycle() }
                } else if (src.bitmap != null) resources.add(Resource(src))
                Src.SrcType.TXT -> safeCall("release text bitmap") { src.bitmap?.recycle() }
                else -> Unit
            }
            src.bitmap = null
        }
        renderer?.shader?.program?.let { program ->
            safeCall("release mix shader") { android.opengl.GLES20.glDeleteProgram(program) }
        }
        if (resources.isNotEmpty()) safeCall("releaseResource") { owner?.releaseResource(resources) }
    }

    private fun parseSrc(config: AnimConfig) {
        config.jsonConfig?.apply {
            srcMap = SrcMap(this)
        }
    }


    private fun parseFrame(config: AnimConfig) {
        config.jsonConfig?.apply {
            frameAll = FrameAll(this)
        }
    }


    private fun createBitmap(): Boolean {
        return try {
            srcMap?.map?.values?.forEach { src ->
                if (src.srcType == Src.SrcType.TXT) {
                    src.bitmap = BitmapUtil.createTxtBitmap(src)
                } else if (src.srcType == Src.SrcType.IMG && src.bitmap == null) {
                    imagePlaceholders.add(src)
                    src.bitmap = BitmapUtil.createEmptyBitmap()
                }
            }
            true
        } catch (e: OutOfMemoryError) {
            ALog.e(TAG, "draw text OOM $e", e)
            false
        }
    }
}
