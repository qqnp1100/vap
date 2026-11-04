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
import android.view.MotionEvent
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimPlayer
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.plugin.IAnimPlugin
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.BitmapUtil
import com.tencent.qgame.animplayer.util.safeCall
import com.tencent.qgame.vap.core.spi.MixGeometry
import com.tencent.qgame.vap.core.spi.MixResourceLoader

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

    private val resourceLoader = MixResourceLoader()
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
        val imageRequests = prepareImageRequests(sources)
        val requests = sources.map { src ->
            when (src.srcType) {
                Src.SrcType.IMG -> MixResourceLoader.Request.Image(
                    fetch = { callback -> request.fetchImage(imageRequests.getValue(src.srcId), callback) },
                    accept = { src.bitmap = it },
                    discard = { bitmap ->
                        safeCall("release late image") {
                            request.releaseResource(listOf(Resource(src).apply { this.bitmap = bitmap }))
                        }
                    }
                )
                Src.SrcType.TXT -> MixResourceLoader.Request.Text(
                    fetch = { callback -> request.fetchText(Resource(src), callback) },
                    accept = { src.txt = it }
                )
                else -> MixResourceLoader.Request.Invalid
            }
        }
        resourceLoader.load(
            session, handler, requests, resourceTimeoutMs, player::isSessionActive,
            prepareBitmaps = {
                createBitmap() && sources.all {
                    it.bitmap != null && it.bitmap?.isRecycled == false && it.bitmap?.config != Bitmap.Config.ALPHA_8
                }
            },
            onError = { ALog.e(TAG, "fetch resource", it) },
            complete = { complete(if (it) Constant.OK else Constant.REPORT_ERROR_TYPE_CONFIG_PLUGIN_MIX) }
        )
    }

    private fun prepareImageRequests(sources: List<Src>): Map<String, Resource> {
        val requests = HashMap<String, Resource>()
        for (src in sources) {
            if (src.srcType == Src.SrcType.IMG) requests[src.srcId] = Resource(src)
        }
        if (requests.isEmpty()) return requests
        val config = player.configManager.config ?: return requests
        val view = player.animView as? AnimView ?: return requests
        val (videoWidth, videoHeight) = view.estimateVideoSize(config.width, config.height) ?: return requests
        if (videoWidth <= 0 || videoHeight <= 0) return requests
        val frames = frameAll?.map ?: return requests
        val layers = (0 until frames.size()).asSequence().flatMap { index ->
            frames.valueAt(index).list.asSequence().map { it.srcId to it.renderRect }
        }
        val targetSizes = MixGeometry.targetSizes(
            layers, requests.keys, config.width, config.height, videoWidth, videoHeight
        )
        for ((sourceId, size) in targetSizes) {
            val request = requests.getValue(sourceId)
            request.targetWidth = size.first
            request.targetHeight = size.second
        }
        return requests
    }

    fun cancelResourceRequests(expectedSession: Long? = null) {
        resourceLoader.cancel(expectedSession)
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
        safeCall("release mix shader") { renderer?.releaseShader() }
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
