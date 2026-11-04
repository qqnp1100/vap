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
package com.tencent.qgame.vap.core.internal.render

import android.view.Surface
import com.tencent.qgame.vap.core.spi.RenderErrorLogger
import com.tencent.qgame.vap.core.spi.EglWindow
import com.tencent.qgame.vap.core.spi.logSafely
import javax.microedition.khronos.egl.*

internal class Egl10Window(private val logger: RenderErrorLogger) : EglWindow {

    companion object {
        private const val TAG = "AnimPlayer.EGLUtil"
        private const val EGL_OPENGL_ES2_BIT = 0x0004
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }

    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglSurface: EGLSurface? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglSurface = EGL10.EGL_NO_SURFACE
        eglContext = EGL10.EGL_NO_CONTEXT
    }

    fun start(surface: Surface) {
        try {
            val api = EGLContext.getEGL() as EGL10
            egl = api
            eglDisplay = api.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != null && eglDisplay != EGL10.EGL_NO_DISPLAY) { "No EGL display" }
            check(api.eglInitialize(eglDisplay, IntArray(2))) { "EGL initialization failed" }
            eglConfig = checkNotNull(chooseConfig()) { "No matching EGL config" }
            eglSurface = api.eglCreateWindowSurface(eglDisplay, eglConfig, surface, null)
            check(eglSurface != null && eglSurface != EGL10.EGL_NO_SURFACE) {
                "EGL surface creation failed: ${Integer.toHexString(api.eglGetError())}"
            }
            eglContext = createContext(api, eglDisplay, eglConfig)
            check(eglContext != null && eglContext != EGL10.EGL_NO_CONTEXT) { "EGL context creation failed" }
            check(api.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "EGL make current failed: ${Integer.toHexString(api.eglGetError())}"
            }
        } catch (e: Throwable) {
            release()
            throw e
        }
    }


    private fun chooseConfig(): EGLConfig? {
        val configsCount = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes =getAttributes()
        val confSize = 1
        if (egl?.eglChooseConfig(eglDisplay, attributes, configs, confSize, configsCount) == true && configsCount[0] > 0) {
            return configs[0]
        }
        return null
    }

    private fun getAttributes(): IntArray {
        return intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,  //指定渲染api类别
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        )
    }

    private fun createContext(egl: EGL10?, eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext? {
        val attrs = intArrayOf(
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
        )
        return egl?.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrs)
    }

    override fun swapBuffers() {
        val api = checkNotNull(egl) { "EGL is not initialized" }
        check(api.eglSwapBuffers(eglDisplay, eglSurface)) {
            "EGL swap failed: ${Integer.toHexString(api.eglGetError())}"
        }
    }

    private fun cleanup(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            logger.logSafely(TAG, "release $name failed", e)
        }
    }

    override fun release() {
        val api = egl
        val display = eglDisplay
        val window = eglSurface
        val context = eglContext
        egl = null
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglSurface = EGL10.EGL_NO_SURFACE
        eglContext = EGL10.EGL_NO_CONTEXT
        eglConfig = null
        if (api != null && display != null && display != EGL10.EGL_NO_DISPLAY) {
            cleanup("current context") {
                api.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            }
            if (window != null && window != EGL10.EGL_NO_SURFACE) {
                cleanup("surface") { api.eglDestroySurface(display, window) }
            }
            if (context != null && context != EGL10.EGL_NO_CONTEXT) {
                cleanup("context") { api.eglDestroyContext(display, context) }
            }
            cleanup("display") { api.eglTerminate(display) }
        }
    }
}
