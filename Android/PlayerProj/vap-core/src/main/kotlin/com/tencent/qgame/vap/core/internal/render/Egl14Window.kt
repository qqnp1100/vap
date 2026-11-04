package com.tencent.qgame.vap.core.internal.render

import android.annotation.TargetApi
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface
import com.tencent.qgame.vap.core.spi.EglWindow

/** The display window can be detached while keeping the decoder texture context alive. */
@TargetApi(17)
internal class Egl14Window(surface: Surface) : DetachableEglWindow {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            checkEgl(display != EGL14.EGL_NO_DISPLAY, "get display")
            val version = IntArray(2)
            initialized = EGL14.eglInitialize(display, version, 0, version, 1)
            checkEgl(initialized, "initialize")
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            checkEgl(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0,
                "choose window and pbuffer config")
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            checkEgl(context != EGL14.EGL_NO_CONTEXT, "create context")
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            checkEgl(pbuffer != EGL14.EGL_NO_SURFACE, "create pbuffer")
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            checkEgl(window != EGL14.EGL_NO_SURFACE, "create window")
            checkEgl(EGL14.eglMakeCurrent(display, window, window, context), "make window current")
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    override fun swapBuffers() {
        checkEgl(window != EGL14.EGL_NO_SURFACE && EGL14.eglSwapBuffers(display, window), "swap")
    }

    override fun detach() {
        if (window == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            // Tear down the context if it cannot be moved. No subsequent draw may use the window.
            release()
            return
        }
        if (!EGL14.eglDestroySurface(display, window)) {
            release()
            return
        }
        window = EGL14.EGL_NO_SURFACE
    }

    override fun release() {
        if (!initialized) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        initialized = false
        window = EGL14.EGL_NO_SURFACE
    }

    private fun checkEgl(success: Boolean, operation: String) {
        check(success) { "EGL $operation failed: 0x${EGL14.eglGetError().toString(16)}" }
    }
}

internal interface DetachableEglWindow : EglWindow {
    fun detach()
}
