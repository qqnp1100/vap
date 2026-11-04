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

import android.opengl.GLES11Ext
import android.opengl.GLES20

/** Draws a single VAPX layer onto the current video output. Call on the GL thread. */
class MixLayerRenderer(
    var vertexArray: GlFloatArray = GlFloatArray(),
    var srcArray: GlFloatArray = GlFloatArray(),
    var maskArray: GlFloatArray = GlFloatArray()
) {
    var shader: MixLayerShader? = null
    internal var geometry: SurfaceGeometry? = null

    fun init(logger: RenderErrorLogger) {
        init(logger, premultiplied = false)
    }

    internal fun init(logger: RenderErrorLogger, premultiplied: Boolean) {
        shader = MixLayerShader(logger, premultiplied)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun renderFrame(
        videoRender: PackedRenderer,
        width: Int,
        height: Int,
        videoWidth: Int,
        videoHeight: Int,
        frame: VideoRect,
        mask: VideoRect,
        rotateMask: Boolean,
        drawWidth: Int,
        drawHeight: Int,
        centerFull: Boolean,
        textureId: Int,
        fillText: Boolean,
        color: Int
    ) {
        val videoTextureId = videoRender.getExternalTexture()
        if (videoTextureId <= 0) return
        val shader = shader ?: return
        shader.useProgram()
        videoRender.bindTextureTransform(shader.uVideoTransformLocation)
        // 定点坐标
        val vertices = VertexUtil.create(width, height, frame, vertexArray.array)
        geometry?.transformVertices(vertices)
        vertexArray.setArray(vertices)
        vertexArray.setVertexAttribPointer(shader.aPositionLocation)

        // src 纹理坐标
        srcArray.setArray(genSrcCoordsArray(srcArray.array, frame.w, frame.h, drawWidth, drawHeight, centerFull))
        srcArray.setVertexAttribPointer(shader.aTextureSrcCoordinatesLocation)
        // 绑定 src纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(shader.uTextureSrcUnitLocation, 0)

        // mask 纹理
        maskArray.setArray(TexCoordsUtil.create(videoWidth, videoHeight, mask, maskArray.array))
        if (rotateMask) {
            maskArray.setArray(TexCoordsUtil.rotate90(maskArray.array))
        }
        maskArray.setVertexAttribPointer(shader.aTextureMaskCoordinatesLocation)
        // 绑定 mask纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(shader.uTextureMaskUnitLocation, 1)

        // 属性处理
        if (fillText) { // // 文字需要颜色填充
            GLES20.glUniform1i(shader.uIsFillLocation,  1)
            GLES20.glUniform4f(
                shader.uColorLocation,
                colorChannel(color, 16), colorChannel(color, 8), colorChannel(color, 0), colorChannel(color, 24)
            )
        } else {
            GLES20.glUniform1i(shader.uIsFillLocation,  0)
            GLES20.glUniform4f(shader.uColorLocation, 0f, 0f, 0f, 0f)
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        // 基于源象素alpha通道值的半透明混合函数
        val sourceRgb = if (shader.premultipliedOutput) GLES20.GL_ONE else GLES20.GL_SRC_ALPHA
        GLES20.glBlendFuncSeparate(sourceRgb, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisable(GLES20.GL_BLEND)

    }

    fun release(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    fun releaseShader() {
        val program = shader?.program ?: return
        shader = null
        GLES20.glDeleteProgram(program)
    }

    /**
     * CENTER_FULL 并不是严格的centerCrop(centerCrop已经前置处理)，此处主要是为防抖动做处理，复杂遮罩情况下需要固定src大小进行绘制防止抖动
     */
    private fun genSrcCoordsArray(array: FloatArray, fw: Int, fh: Int, sw: Int, sh: Int, centerFull: Boolean): FloatArray {
        return if (centerFull) {
            if (fw <= sw && fh <= sh) {
                // 中心对齐，不拉伸
                val gw = (sw - fw) / 2
                val gh = (sh - fh) / 2
                TexCoordsUtil.create(sw, sh, VideoRect(gw, gh, fw, fh), array)
            } else { // centerCrop
                val fScale = fw * 1.0f / fh
                val sScale = sw * 1.0f / sh
                val srcRect = if (fScale > sScale) {
                    val w = sw
                    val x = 0
                    val h = (sw / fScale).toInt()
                    val y = (sh - h) / 2

                    VideoRect(x, y, w, h)
                } else {
                    val h = sh
                    val y = 0
                    val w = (sh * fScale).toInt()
                    val x = (sw - w) / 2
                    VideoRect(x, y, w, h)
                }
                TexCoordsUtil.create(sw, sh, srcRect, array)
            }
        } else { // 默认 fitXY
            TexCoordsUtil.create(fw, fh, VideoRect(0, 0, fw, fh), array)
        }
    }

    private fun colorChannel(color: Int, shift: Int): Float = (color.ushr(shift) and 0xff) / 255f
}
