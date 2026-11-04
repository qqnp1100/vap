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
package com.tencent.qgame.vap.core.spi

import android.graphics.Bitmap

/** Owns only the uploaded GL texture. Its bitmap is borrowed from the caller. */
class MaskLayerTexture(private val logger: RenderErrorLogger) {
    var textureId = 0
        private set

    var bitmap: Bitmap? = null
        private set

    /** Called with the owning GL context current. */
    fun updateTexture(): Int {
        if (textureId > 0) return textureId
        val bitmap = bitmap ?: return 0
        if (bitmap.isRecycled) return 0
        textureId = TextureLoadUtil.loadTexture(bitmap, logger)
        return textureId
    }

    fun replaceBitmap(bitmap: Bitmap?) {
        releaseTexture()
        this.bitmap = bitmap
    }

    fun release() {
        bitmap = null
        releaseTexture()
    }

    private fun releaseTexture() {
        val oldTexture = textureId
        textureId = 0
        if (oldTexture > 0) TextureLoadUtil.releaseTexure(oldTexture)
    }
}
