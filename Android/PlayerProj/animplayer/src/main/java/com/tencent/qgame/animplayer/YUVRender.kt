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
package com.tencent.qgame.animplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import com.tencent.qgame.animplayer.util.GlFloatArray
import com.tencent.qgame.animplayer.util.ShaderUtil.createProgram
import com.tencent.qgame.animplayer.util.TexCoordsUtil
import com.tencent.qgame.animplayer.util.VertexUtil
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class YUVRender (surfaceTexture: SurfaceTexture): IRenderListener {

    companion object {
        private const val TAG = "${Constant.TAG}.YUVRender"
    }

    private val vertexArray = GlFloatArray()
    private val alphaArray = GlFloatArray()
    private val rgbArray = GlFloatArray()

    private var shaderProgram = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceSizeChanged = false

    //顶点位置
    private var avPosition = 0

    //rgb纹理位置
    private var rgbPosition = 0

    //alpha纹理位置
    private var alphaPosition = 0

    //shader  yuv变量
    private var samplerY = 0
    private var samplerU = 0
    private var samplerV = 0
    private var textureId = IntArray(3)
    private var convertMatrixUniform = 0
    private var convertOffsetUniform = 0

    private var textureWidth = 0
    private var textureHeight = 0
    private val stagedFrame = AtomicReference<YuvFrame?>()
    private val eglUtil = EGLUtil()

    // YUV offset
    private val YUV_OFFSET = floatArrayOf(
            0f, -0.501960814f, -0.501960814f
    )

    // RGB coefficients
    private val YUV_MATRIX = floatArrayOf(
            1f, 1f, 1f,
            0f, -0.3441f, 1.772f,
            1.402f, -0.7141f, 0f
    )

    init {
        try {
            eglUtil.start(surfaceTexture)
            initRender()
        } catch (e: Throwable) {
            destroyRender()
            throw e
        }
    }

    override fun initRender() {
        shaderProgram = createProgram(YUVShader.VERTEX_SHADER, YUVShader.FRAGMENT_SHADER)
        //获取顶点坐标字段
        avPosition = GLES20.glGetAttribLocation(shaderProgram, "v_Position")
        //获取纹理坐标字段
        rgbPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateRgb")
        alphaPosition = GLES20.glGetAttribLocation(shaderProgram, "vTexCoordinateAlpha")

        //获取yuv字段
        samplerY = GLES20.glGetUniformLocation(shaderProgram, "sampler_y")
        samplerU = GLES20.glGetUniformLocation(shaderProgram, "sampler_u")
        samplerV = GLES20.glGetUniformLocation(shaderProgram, "sampler_v")
        convertMatrixUniform = GLES20.glGetUniformLocation(shaderProgram, "convertMatrix")
        convertOffsetUniform = GLES20.glGetUniformLocation(shaderProgram, "offset")
        //创建3个纹理
        GLES20.glGenTextures(textureId.size, textureId, 0)

        //绑定纹理
        for (id in textureId) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
    }

    override fun renderFrame() {
        val frame = stagedFrame.getAndSet(null) ?: return
        renderFrame(frame)
    }

    internal fun renderFrame(frame: YuvFrame) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (surfaceSizeChanged) {
            surfaceSizeChanged = false
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
        draw(frame)
    }

    override fun clearFrame() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        eglUtil.swapBuffers()
    }

    override fun destroyRender() {
        try {
            releaseTexture()
        } finally {
            try {
                if (shaderProgram != 0) GLES20.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            } finally {
                eglUtil.release()
            }
        }
    }

    override fun setAnimConfig(config: AnimConfig) {
        vertexArray.setArray(VertexUtil.create(config.width, config.height, PointRect(0, 0, config.width, config.height), vertexArray.array))
        val alpha = TexCoordsUtil.create(config.videoWidth, config.videoHeight, config.alphaPointRect, alphaArray.array)
        val rgb = TexCoordsUtil.create(config.videoWidth, config.videoHeight, config.rgbPointRect, rgbArray.array)
        alphaArray.setArray(alpha)
        rgbArray.setArray(rgb)
    }

    override fun updateViewPort(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        surfaceSizeChanged = true
    }

    override fun getExternalTexture(): Int {
        return textureId[0]
    }

    override fun releaseTexture() {
        GLES20.glDeleteTextures(textureId.size, textureId, 0)
        textureId.fill(0)
        textureWidth = 0
        textureHeight = 0
        stagedFrame.set(null)
    }

    override fun swapBuffers() {
        eglUtil.swapBuffers()
    }

    override fun setYUVData(width: Int, height: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?) {
        if (y == null || u == null || v == null) {
            stagedFrame.set(null)
            return
        }
        val frame = YuvFrame(width, height)
        require(y.size == frame.y.capacity() && u.size == frame.u.capacity() && v.size == frame.v.capacity())
        frame.y.put(y).flip()
        frame.u.put(u).flip()
        frame.v.put(v).flip()
        stagedFrame.set(frame)
    }

    private fun draw(frame: YuvFrame) {
        GLES20.glUseProgram(shaderProgram)
        vertexArray.setVertexAttribPointer(avPosition)
        alphaArray.setVertexAttribPointer(alphaPosition)
        rgbArray.setVertexAttribPointer(rgbPosition)

        val resize = textureWidth != frame.width || textureHeight != frame.height
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        try {
            upload(0, frame.width, frame.height, frame.y, resize)
            upload(1, frame.chromaWidth, frame.chromaHeight, frame.u, resize)
            upload(2, frame.chromaWidth, frame.chromaHeight, frame.v, resize)
            if (resize) {
                val error = GLES20.glGetError()
                check(error == GLES20.GL_NO_ERROR) { "YUV texture allocation failed: GL error $error" }
            }
            textureWidth = frame.width
            textureHeight = frame.height
        } finally {
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        }

        GLES20.glUniform1i(samplerY, 0)
        GLES20.glUniform1i(samplerU, 1)
        GLES20.glUniform1i(samplerV, 2)
        GLES20.glUniform3fv(convertOffsetUniform, 1, YUV_OFFSET, 0)
        GLES20.glUniformMatrix3fv(convertMatrixUniform, 1, false, YUV_MATRIX, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(avPosition)
        GLES20.glDisableVertexAttribArray(rgbPosition)
        GLES20.glDisableVertexAttribArray(alphaPosition)
    }

    private fun upload(index: Int, width: Int, height: Int, data: ByteBuffer, resize: Boolean) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[index])
        if (resize) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
        }
    }
}
