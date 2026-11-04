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

import android.opengl.GLES20

data class MaskRegion(val rect: VideoRect, val referenceWidth: Int, val referenceHeight: Int)

/** Applies the existing independent alpha mask blend on the current GL output. */
class MaskLayerRenderer(var vertexArray: GlFloatArray = GlFloatArray()) {
    var shader: MaskLayerShader? = null
    private val maskArray = GlFloatArray()

    fun init(edgeBlur: Boolean, logger: RenderErrorLogger) {
        shader = MaskLayerShader(edgeBlur, logger)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun renderFrame(
        videoTextureId: Int,
        width: Int,
        height: Int,
        texture: MaskLayerTexture,
        textureRegion: MaskRegion,
        position: MaskRegion?
    ) {
        if (videoTextureId <= 0) return
        val shader = shader ?: return
        var maskTexId = texture.textureId
        val maskBitmap = texture.bitmap ?: return
        val positionRegion = position ?: MaskRegion(VideoRect(0, 0, width, height), width, height)
        if (textureRegion.referenceWidth <= 0 || textureRegion.referenceHeight <= 0 ||
            positionRegion.referenceWidth <= 0 || positionRegion.referenceHeight <= 0 ||
            textureRegion.rect.w <= 0 || textureRegion.rect.h <= 0 ||
            positionRegion.rect.w <= 0 || positionRegion.rect.h <= 0) return
        shader.useProgram()
        // 顶点坐标
        vertexArray.setArray(
            VertexUtil.create(
                positionRegion.referenceWidth,
                positionRegion.referenceHeight,
                positionRegion.rect,
                vertexArray.array
            )
        )
        vertexArray.setVertexAttribPointer(shader.aPositionLocation)

        if (maskTexId <= 0 && !maskBitmap.isRecycled) {
            maskTexId = texture.updateTexture()
        }
        if (maskTexId > 0) {
            maskArray.setArray(
                TexCoordsUtil.create(
                    textureRegion.referenceWidth,
                    textureRegion.referenceHeight,
                    textureRegion.rect,
                    maskArray.array
                )
            )
            maskArray.setVertexAttribPointer(shader.aTextureMaskCoordinatesLocation)
            // 绑定alpha纹理
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
            GLES20.glTexParameterf(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST.toFloat()
            )
            GLES20.glTexParameterf(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glUniform1i(shader.uTextureMaskUnitLocation, 0)

            GLES20.glEnable(GLES20.GL_BLEND)
            // 基于源象素alpha通道值的半透明混合函数
            GLES20.glBlendFuncSeparate(
                GLES20.GL_ONE,
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ZERO,
                GLES20.GL_SRC_ALPHA
            )
            // draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisable(GLES20.GL_BLEND)
        }

    }

    fun release() {
        val currentShader = shader
        shader = null
        currentShader?.release()
    }
}
