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
import android.view.Surface
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.vap.core.spi.EglWindow
import com.tencent.qgame.vap.core.spi.RenderErrorLogger
import com.tencent.qgame.vap.core.spi.VideoRect
import com.tencent.qgame.vap.core.spi.VideoRenderConfig
import com.tencent.qgame.vap.core.spi.createEglWindow

class EGLUtil {
    companion object {}

    private val output = LegacyRenderSurface()
    private var window: EglWindow? = null

    fun start(surfaceTexture: SurfaceTexture) {
        window = output.start(surfaceTexture) { createEglWindow(it, legacyRenderLogger) }
    }

    fun swapBuffers() {
        checkNotNull(window) { "EGL is not initialized" }.swapBuffers()
    }

    fun release() {
        val current = window
        window = null
        try {
            current?.release()
        } finally {
            output.release()
        }
    }
}

internal val legacyRenderLogger = RenderErrorLogger { tag, message, cause ->
    if (cause == null) ALog.e(tag, message) else ALog.e(tag, message, cause)
}

internal fun AnimConfig.toVideoRenderConfig() = VideoRenderConfig(
    width, height, videoWidth, videoHeight,
    VideoRect(rgbPointRect.x, rgbPointRect.y, rgbPointRect.w, rgbPointRect.h),
    VideoRect(alphaPointRect.x, alphaPointRect.y, alphaPointRect.w, alphaPointRect.h)
)

/** The compatibility layer owns the Surface that wraps the caller's SurfaceTexture. */
internal class LegacyRenderSurface {
    private var surface: Surface? = null

    fun <T> start(texture: SurfaceTexture, create: (Surface) -> T): T {
        check(surface == null) { "EGL is already initialized" }
        val output = Surface(texture)
        surface = output
        try {
            return create(output)
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun release() {
        val output = surface
        surface = null
        try {
            output?.release()
        } catch (error: Throwable) {
            ALog.e("${Constant.TAG}.EGLUtil", "release native surface failed", error)
        }
    }
}
