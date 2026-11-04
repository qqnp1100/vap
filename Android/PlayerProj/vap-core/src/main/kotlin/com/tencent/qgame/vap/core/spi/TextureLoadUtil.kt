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
import android.opengl.GLES20
import android.opengl.GLUtils

/** Bitmap texture upload shared by the playback overlays. */
object TextureLoadUtil {
    private const val TAG = "TextureUtil"
    fun loadTexture(bitmap: Bitmap?, logger: RenderErrorLogger): Int {
        if (bitmap == null || bitmap.isRecycled) {
            if (bitmap?.isRecycled == true) logger.logSafely(TAG, "bitmap isRecycled")
            return 0
        }
        val textureObjectIds = IntArray(1)
        GLES20.glGenTextures(1, textureObjectIds, 0)
        val texture = textureObjectIds[0]
        if (texture == 0) return 0
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            // NPOT textures in GLES 2.0 must clamp and cannot require mipmaps.
            val powerOfTwo = (bitmap.width and (bitmap.width - 1)) == 0 &&
                (bitmap.height and (bitmap.height - 1)) == 0
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                if (powerOfTwo) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            if (powerOfTwo) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            return texture
        } catch (e: Throwable) {
            GLES20.glDeleteTextures(1, textureObjectIds, 0)
            throw e
        } finally {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    fun releaseTexure(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}