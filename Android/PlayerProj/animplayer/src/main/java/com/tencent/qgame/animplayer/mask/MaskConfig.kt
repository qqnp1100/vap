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

import android.graphics.Bitmap
import com.tencent.qgame.animplayer.PointRect
import com.tencent.qgame.animplayer.RefVec2
import com.tencent.qgame.animplayer.legacyRenderLogger
import com.tencent.qgame.vap.core.spi.MaskLayerTexture
import com.tencent.qgame.vap.core.spi.MaskRegion
import com.tencent.qgame.vap.core.spi.VideoRect

class MaskConfig() {
    var maskTexPair: Pair<PointRect, RefVec2>? = null
        set(value) {
            field = value
            textureRegion = value?.toRegion()
        }
    var maskPositionPair: Pair<PointRect, RefVec2>? = null
        set(value) {
            field = value
            positionRegion = value?.toRegion()
        }

    internal var textureRegion: MaskRegion? = null
        private set
    internal var positionRegion: MaskRegion? = null
        private set
    internal val texture = MaskLayerTexture(legacyRenderLogger)

    constructor(bitmap: Bitmap?, positionPair: Pair<PointRect, RefVec2>?, texPair: Pair<PointRect, RefVec2>?) : this() {
        maskPositionPair = positionPair
        maskTexPair = texPair
        texture.replaceBitmap(bitmap)
    }

    fun getMaskTexId(): Int = texture.textureId

    /** Called only by the owning render thread while its GL context is current. */
    fun updateMaskTex(): Int = texture.updateTexture()

    val alphaMaskBitmap: Bitmap? get() = texture.bitmap

    /** Render-thread-owned once a texture has been created. */
    fun safeSetMaskBitmapAndReleasePre(bitmap: Bitmap?) = texture.replaceBitmap(bitmap)

    fun release() {
        maskTexPair = null
        maskPositionPair = null
        texture.release()
    }

    private fun Pair<PointRect, RefVec2>.toRegion(): MaskRegion =
        MaskRegion(VideoRect(first.x, first.y, first.w, first.h), second.w, second.h)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is MaskConfig && alphaMaskBitmap === other.alphaMaskBitmap &&
            maskTexPair == other.maskTexPair && maskPositionPair == other.maskPositionPair
    }

    override fun hashCode(): Int {
        var result = alphaMaskBitmap?.let { System.identityHashCode(it) } ?: 0
        result = 31 * result + (maskTexPair?.hashCode() ?: 0)
        result = 31 * result + (maskPositionPair?.hashCode() ?: 0)
        return result
    }
}
