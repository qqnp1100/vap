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

class MaskLayerShader(edgeBlur: Boolean, logger: RenderErrorLogger) {
    companion object {

        private const val VERTEX =
            "attribute vec4 vPosition;\n" +
                    "attribute vec4 vTexCoordinateAlphaMask;\n" +
                    "varying vec2 v_TexCoordinateAlphaMask;\n" +
                    "\n" +
                    "void main() {\n" +
                    "    v_TexCoordinateAlphaMask = vec2(vTexCoordinateAlphaMask.x, vTexCoordinateAlphaMask.y);\n" +
                    "    gl_Position = vPosition;\n" +
                    "}"
        //边缘做高斯模糊
        private const val FRAGMENT_BLUR_EDGE =
                    "precision mediump float;\n" +
                    "uniform sampler2D uTextureAlphaMask;\n" +
                    "varying vec2 v_TexCoordinateAlphaMask;\n" +
                    "mat3 weight = mat3(0.0625,0.125,0.0625,0.125,0.25,0.125,0.0625,0.125,0.0625);\n " +
                    "int coreSize=3;\n" +
                    "float texelOffset = .01;\n" +
                    "\n" +
                    "void main() {\n" +
                    "   float alphaResult = 0.;\n" +
                    "   for(int y = 0; y < coreSize; y++) {\n" +
                    "       for(int x = 0;x < coreSize; x++) {\n" +
                    "           alphaResult += texture2D(uTextureAlphaMask, vec2(v_TexCoordinateAlphaMask.x + (-1.0 + float(x)) * texelOffset,v_TexCoordinateAlphaMask.y + (-1.0 + float(y)) * texelOffset)).a * weight[x][y];\n" +
                    "       }\n" +
                    "    }\n" +
                    "    gl_FragColor = vec4(0, 0, 0, alphaResult);\n" +
                    "}"

        private const val FRAGMENT_NO_BLUR_EDGE =
            "precision mediump float;\n" +
                    "uniform sampler2D uTextureAlphaMask;\n" +
                    "varying vec2 v_TexCoordinateAlphaMask;\n" +
                    "\n" +
                    "void main () {\n" +
                    "    vec4 alphaMaskColor = texture2D(uTextureAlphaMask, v_TexCoordinateAlphaMask);\n" +
                    "    gl_FragColor = vec4(0, 0, 0, alphaMaskColor.a);\n" +
                    "}"

        // Uniform constants
        private const val U_TEXTURE_ALPHA_MASK_UNIT = "uTextureAlphaMask"

        // Attribute constants
        private const val A_POSITION = "vPosition"
        private const val A_TEXTURE_MASK_COORDINATES = "vTexCoordinateAlphaMask"
    }

    // Shader program
    private var program: Int

    // Uniform locations
    val uTextureMaskUnitLocation: Int
    // Attribute locations
    val aPositionLocation: Int
    val aTextureMaskCoordinatesLocation: Int

    init {
        program = ShaderUtil.createProgram(VERTEX, if (edgeBlur) FRAGMENT_BLUR_EDGE else FRAGMENT_NO_BLUR_EDGE, logger)
        uTextureMaskUnitLocation = GLES20.glGetUniformLocation(program, U_TEXTURE_ALPHA_MASK_UNIT)

        aPositionLocation = GLES20.glGetAttribLocation(program, A_POSITION)
        aTextureMaskCoordinatesLocation = GLES20.glGetAttribLocation(program, A_TEXTURE_MASK_COORDINATES)
    }

    fun useProgram() {
        GLES20.glUseProgram(program)
    }

    fun release() {
        val oldProgram = program
        program = 0
        if (oldProgram > 0) GLES20.glDeleteProgram(oldProgram)
    }

}
