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

import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.Render
import com.tencent.qgame.animplayer.legacyRenderLogger
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.GlFloatArray
import com.tencent.qgame.animplayer.util.TextureLoadUtil
import com.tencent.qgame.vap.core.spi.MixLayerRenderer

/**
 * vapx 渲染
 */
class MixRender(private val mixAnimPlugin: MixAnimPlugin) {
    companion object {
        private const val TAG = "${Constant.TAG}.MixRender"
    }

    var shader: MixShader? = null
        set(value) {
            field = value
            renderer.shader = value?.coreShader
        }
    var vertexArray = GlFloatArray()
        set(value) {
            field = value
            renderer.vertexArray = value.coreArray
        }
    var srcArray = GlFloatArray()
        set(value) {
            field = value
            renderer.srcArray = value.coreArray
        }
    var maskArray = GlFloatArray()
        set(value) {
            field = value
            renderer.maskArray = value.coreArray
        }

    private val renderer = MixLayerRenderer(vertexArray.coreArray, srcArray.coreArray, maskArray.coreArray)

    fun init() {
        renderer.init(legacyRenderLogger)
        shader = renderer.shader?.let(::MixShader)
        mixAnimPlugin.srcMap?.map?.values?.forEach { src ->
            ALog.i(TAG, "init srcId=${src.srcId}")
            src.srcTextureId = TextureLoadUtil.loadTexture(src.bitmap)
            ALog.i(TAG, "textureProgram=${shader?.program},textureId=${src.srcTextureId}")
        }
    }

    fun renderFrame(config: AnimConfig, frame: Frame, src: Src) {
        val videoRender = mixAnimPlugin.player.decoder?.render as? Render ?: return
        renderer.renderFrame(
            videoRender.packedRenderer,
            config.width, config.height, config.videoWidth, config.videoHeight,
            frame.renderRect, frame.maskRect, frame.mt == 90,
            src.drawWidth, src.drawHeight, src.fitType == Src.FitType.CENTER_FULL, src.srcTextureId,
            src.srcType == Src.SrcType.TXT && mixAnimPlugin.autoTxtColorFill, src.color
        )
    }

    fun release(textureId: Int) = renderer.release(textureId)

    internal fun releaseShader() {
        renderer.releaseShader()
        shader = null
    }
}
