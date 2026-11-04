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
package com.tencent.qgame.animplayer.mask

import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.legacyRenderLogger
import com.tencent.qgame.animplayer.util.GlFloatArray
import com.tencent.qgame.vap.core.spi.MaskLayerRenderer

class MaskRender(private val maskAnimPlugin: MaskAnimPlugin) {
    companion object {}

    var maskShader: MaskShader? = null
        set(value) {
            field = value
            renderer.shader = value?.coreShader
        }
    var vertexArray = GlFloatArray()
        set(value) {
            field = value
            renderer.vertexArray = value.coreArray
        }

    private val renderer = MaskLayerRenderer(vertexArray.coreArray)

    fun initMaskShader(edgeBlur: Boolean) {
        renderer.init(edgeBlur, legacyRenderLogger)
        maskShader = renderer.shader?.let(::MaskShader)
    }

    fun renderFrame(config: AnimConfig) {
        val videoTextureId = maskAnimPlugin.player.decoder?.render?.getExternalTexture() ?: return
        val mask = config.maskConfig ?: return
        val textureRegion = mask.textureRegion ?: return
        renderer.renderFrame(
            videoTextureId, config.width, config.height, mask.texture, textureRegion, mask.positionRegion
        )
    }

    fun release() {
        renderer.release()
        maskShader = null
    }
}
