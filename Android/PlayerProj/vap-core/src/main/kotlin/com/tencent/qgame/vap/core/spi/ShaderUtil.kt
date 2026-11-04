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

/** Shared GLES helper versioned with the host libraries. */
object ShaderUtil {
    private const val TAG = "AnimPlayer.ShaderUtil"


    fun createProgram(vertexSource: String, fragmentSource: String, logger: RenderErrorLogger): Int {
        var vertexShaderHandle = 0
        var fragmentShaderHandle = 0
        try {
            vertexShaderHandle = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource, logger)
            fragmentShaderHandle = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource, logger)
            return createAndLinkProgram(vertexShaderHandle, fragmentShaderHandle, logger)
        } finally {
            if (vertexShaderHandle != 0) GLES20.glDeleteShader(vertexShaderHandle)
            if (fragmentShaderHandle != 0) GLES20.glDeleteShader(fragmentShaderHandle)
        }
    }


    private fun compileShader(shaderType: Int, shaderSource: String, logger: RenderErrorLogger): Int {
        var shaderHandle = GLES20.glCreateShader(shaderType)

        if (shaderHandle != 0) {
            GLES20.glShaderSource(shaderHandle, shaderSource)
            GLES20.glCompileShader(shaderHandle)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shaderHandle, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                logger.logSafely(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shaderHandle), null)
                GLES20.glDeleteShader(shaderHandle)
                shaderHandle = 0
            }
        }
        if (shaderHandle == 0) {
            throw RuntimeException("Error creating shader.")
        }
        return shaderHandle
    }


    private fun createAndLinkProgram(vertexShaderHandle: Int, fragmentShaderHandle: Int, logger: RenderErrorLogger): Int {
        var programHandle = GLES20.glCreateProgram()

        if (programHandle != 0) {
            GLES20.glAttachShader(programHandle, vertexShaderHandle)
            GLES20.glAttachShader(programHandle, fragmentShaderHandle)
            GLES20.glLinkProgram(programHandle)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                logger.logSafely(TAG, "Error compiling program: " + GLES20.glGetProgramInfoLog(programHandle), null)
                GLES20.glDeleteProgram(programHandle)
                programHandle = 0
            }
        }
        if (programHandle == 0) {
            throw RuntimeException("Error creating program.")
        }
        return programHandle
    }

}
