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
import com.tencent.qgame.vap.core.spi.createYuvRenderer

class YUVRender(surfaceTexture: SurfaceTexture) : IRenderListener {
    companion object {}

    private val output = LegacyRenderSurface()
    private val delegate = output.start(surfaceTexture) { createYuvRenderer(it, legacyRenderLogger) }

    override fun initRender() = delegate.initRender()

    override fun renderFrame() = delegate.renderFrame()

    override fun clearFrame() = delegate.clearFrame()

    override fun destroyRender() {
        try {
            delegate.destroyRender()
        } finally {
            output.release()
        }
    }

    override fun setAnimConfig(config: AnimConfig) = delegate.setConfig(config.toVideoRenderConfig())

    override fun updateViewPort(width: Int, height: Int) = delegate.updateViewPort(width, height)

    override fun getExternalTexture(): Int = delegate.getExternalTexture()

    override fun releaseTexture() = delegate.releaseTexture()

    override fun swapBuffers() = delegate.swapBuffers()

    internal fun renderFrame(frame: YuvFrame) = delegate.renderFrame(frame)

    override fun setYUVData(width: Int, height: Int, y: ByteArray?, u: ByteArray?, v: ByteArray?) =
        delegate.setYUVData(width, height, y, u, v)
}
