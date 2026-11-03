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
import com.tencent.qgame.animplayer.util.TextureLoadUtil

class MaskConfig() {
    var maskTexPair: Pair<PointRect, RefVec2>? = null //遮罩坐标矩形
    var maskPositionPair: Pair<PointRect, RefVec2>? = null //内容坐标矩形

    constructor(bitmap: Bitmap?, positionPair :Pair<PointRect, RefVec2>?, texPair: Pair<PointRect, RefVec2>?) : this() {
        maskPositionPair = positionPair
        maskTexPair = texPair
        alphaMaskBitmap = bitmap
    }

    private var maskTexId = 0
    fun getMaskTexId() : Int {
        return maskTexId
    }

    /** Called only by the owning render thread while its GL context is current. */
    fun updateMaskTex() : Int {
        if (maskTexId > 0) return maskTexId
        val bitmap = alphaMaskBitmap ?: return 0
        if (bitmap.isRecycled) return 0
        maskTexId = TextureLoadUtil.loadTexture(bitmap)
        return  maskTexId
    }

    // Borrowed from the caller; replacing/releasing this config never recycles the Bitmap.
    var alphaMaskBitmap: Bitmap? = null //遮罩
        private set(value) {
            field = value
        }
    
    /** Render-thread-owned once a texture has been created. */
    fun safeSetMaskBitmapAndReleasePre(bitmap: Bitmap?) {
        releaseTexture()
        alphaMaskBitmap = bitmap
    }
    

    fun release() {
        alphaMaskBitmap = null
        maskTexPair = null
        maskPositionPair = null
        releaseTexture()
    }

    private fun releaseTexture() {
        val texture = maskTexId
        maskTexId = 0
        if (texture > 0) TextureLoadUtil.releaseTexure(texture)
    }

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
