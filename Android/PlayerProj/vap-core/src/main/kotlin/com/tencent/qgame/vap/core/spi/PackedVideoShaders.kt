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


/** Shader source shared with legacy renderer clients. */
object PackedVideoShaders {

    // VAP rectangles start at the top left, while SurfaceTexture takes GL coordinates.
    const val VIDEO_TEXTURE_COORDS = "uniform mat4 uVideoTransform;\n" +
            "vec2 videoTexCoord(vec2 uv) {\n" +
            "    return (uVideoTransform * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;\n" +
            "}\n"

    const val VERTEX_SHADER = "attribute vec4 vPosition;\n" +
            "attribute vec4 vTexCoordinateAlpha;\n" +
            "attribute vec4 vTexCoordinateRgb;\n" +
            "varying vec2 v_TexCoordinateAlpha;\n" +
            "varying vec2 v_TexCoordinateRgb;\n" +
            VIDEO_TEXTURE_COORDS +
            "void main() {\n" +
            "    v_TexCoordinateAlpha = videoTexCoord(vTexCoordinateAlpha.xy);\n" +
            "    v_TexCoordinateRgb = videoTexCoord(vTexCoordinateRgb.xy);\n" +
            "    gl_Position = vPosition;\n" +
            "}"
    const val FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES texture;\n" +
            "varying vec2 v_TexCoordinateAlpha;\n" +
            "varying vec2 v_TexCoordinateRgb;\n" +
            "\n" +
            "void main () {\n" +
            "    vec4 alphaColor = texture2D(texture, v_TexCoordinateAlpha);\n" +
            "    vec4 rgbColor = texture2D(texture, v_TexCoordinateRgb);\n" +
            "    gl_FragColor = vec4(rgbColor.r, rgbColor.g, rgbColor.b, alphaColor.r);\n" +
            "}"
}
