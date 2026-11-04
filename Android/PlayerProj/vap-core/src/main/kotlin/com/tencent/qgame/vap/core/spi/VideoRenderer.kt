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

import android.graphics.SurfaceTexture
import android.view.Surface
import com.tencent.qgame.animplayer.YuvFrame
import com.tencent.qgame.vap.core.internal.render.Egl10Window
import com.tencent.qgame.vap.core.internal.render.PackedVideoRenderer
import com.tencent.qgame.vap.core.internal.render.YuvVideoRenderer

data class VideoRect(val x: Int, val y: Int, val w: Int, val h: Int)

data class VideoRenderConfig(
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val rgb: VideoRect,
    val alpha: VideoRect
)

fun interface RenderErrorLogger {
    fun log(tag: String, message: String, cause: Throwable?)
}

internal fun RenderErrorLogger.logSafely(tag: String, message: String, cause: Throwable? = null) {
    try {
        log(tag, message, cause)
    } catch (_: Throwable) {
        // An application logger must not interrupt GL/EGL cleanup.
    }
}

/** Host-facing rendering commands. Invoke on the thread owning the EGL context. */
interface VideoRenderer {
    fun initRender()
    fun renderFrame()
    fun clearFrame()
    fun destroyRender()
    fun setConfig(config: VideoRenderConfig)
    fun updateViewPort(width: Int, height: Int)
    fun getExternalTexture(): Int
    fun releaseTexture()
    fun swapBuffers()
}

interface PackedRenderer : VideoRenderer {
    fun updateTextureTransform(texture: SurfaceTexture)
    fun bindTextureTransform(location: Int)
}

interface YuvRenderer : VideoRenderer {
    fun renderFrame(frame: YuvFrame)
    fun setYUVData(width: Int, height: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?)
}

interface EglWindow {
    fun swapBuffers()
    fun release()
}

/** Creates an EGL window on a borrowed [surface]. Release the window before releasing the surface. */
fun createEglWindow(surface: Surface, logger: RenderErrorLogger): EglWindow =
    Egl10Window(logger).apply { start(surface) }

/** Creates a packed-video renderer on a borrowed [surface]. */
fun createPackedRenderer(surface: Surface, logger: RenderErrorLogger): PackedRenderer =
    PackedVideoRenderer(surface, logger)

/** Creates a legacy YUV renderer on a borrowed [surface]. */
fun createYuvRenderer(surface: Surface, logger: RenderErrorLogger): YuvRenderer =
    YuvVideoRenderer(surface, logger)
