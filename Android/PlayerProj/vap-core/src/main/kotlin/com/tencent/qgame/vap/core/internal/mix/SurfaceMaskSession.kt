package com.tencent.qgame.vap.core.internal.mix

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.Build
import com.tencent.qgame.vap.core.spi.GlFloatArray
import com.tencent.qgame.vap.core.spi.MaskRegion
import com.tencent.qgame.vap.core.spi.RenderErrorLogger
import com.tencent.qgame.vap.core.spi.ShaderUtil
import com.tencent.qgame.vap.core.spi.SurfaceGeometry
import com.tencent.qgame.vap.core.spi.SurfaceMask
import com.tencent.qgame.vap.core.spi.TexCoordsUtil
import com.tencent.qgame.vap.core.spi.TextureLoadUtil
import com.tencent.qgame.vap.core.spi.VertexUtil
import com.tencent.qgame.vap.core.spi.VideoRect

/** Multiplies the already composited premultiplied RGBA by the borrowed mask alpha. */
@TargetApi(23)
internal class SurfaceMaskSession(private val logger: RenderErrorLogger) {
    private val vertices = GlFloatArray()
    private val coordinates = GlFloatArray()
    private var uploadedMask: SurfaceMask? = null
    private var textureId = 0
    private var program = 0
    private var positionLocation = -1
    private var uvLocation = -1
    private var samplerLocation = -1
    private var blurLocation = -1
    private var stepLocation = -1

    fun render(mask: SurfaceMask?, width: Int, height: Int, geometry: SurfaceGeometry? = null) {
        if (mask == null) {
            updateTexture(null)
            return
        }
        val image = mask.bitmap
        val target = mask.position ?: MaskRegion(VideoRect(0, 0, width, height), width, height)
        val source = mask.texture ?: MaskRegion(VideoRect(0, 0, image.width, image.height), image.width, image.height)
        require(target.referenceWidth > 0 && target.referenceHeight > 0 &&
            target.rect.w > 0 && target.rect.h > 0) { "Invalid VAP mask position" }
        val rect = source.rect
        require(source.referenceWidth > 0 && source.referenceHeight > 0 &&
            rect.x >= 0 && rect.y >= 0 && rect.w > 0 && rect.h > 0 &&
            rect.x.toLong() + rect.w <= source.referenceWidth &&
            rect.y.toLong() + rect.h <= source.referenceHeight) { "Invalid VAP mask texture region" }
        if (program == 0) createProgram()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        updateTexture(mask)
        GLES20.glUseProgram(program)
        val points = VertexUtil.create(target.referenceWidth, target.referenceHeight, target.rect, vertices.array)
        geometry?.transformVertices(points)
        vertices.setArray(points)
        vertices.setVertexAttribPointer(positionLocation)
        coordinates.setArray(TexCoordsUtil.create(source.referenceWidth, source.referenceHeight, rect, coordinates.array))
        coordinates.setVertexAttribPointer(uvLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniform1i(blurLocation, if (mask.edgeBlur) 1 else 0)
        GLES20.glUniform2f(stepLocation, 1f / image.width, 1f / image.height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun releaseGl() {
        updateTexture(null)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    private fun updateTexture(next: SurfaceMask?) {
        if (uploadedMask === next) return
        val image = next?.bitmap
        val nextId = if (image == null) 0 else {
            val maxSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
            require(!image.isRecycled && image.width.toLong() * image.height <= MAX_PIXELS &&
                image.width <= maxSize[0] && image.height <= maxSize[0] &&
                (Build.VERSION.SDK_INT < 26 || image.config != Bitmap.Config.HARDWARE)) {
                "Unsupported VAP mask bitmap"
            }
            TextureLoadUtil.loadTexture(image, logger).also { check(it != 0) { "Could not upload VAP mask bitmap" } }
        }
        TextureLoadUtil.releaseTexure(textureId)
        textureId = nextId
        uploadedMask = next
    }

    private fun createProgram() {
        program = ShaderUtil.createProgram(VERTEX, FRAGMENT, logger)
        check(program != 0) { "Could not initialize VAP mask shader" }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        uvLocation = GLES20.glGetAttribLocation(program, "aUv")
        samplerLocation = GLES20.glGetUniformLocation(program, "uMask")
        blurLocation = GLES20.glGetUniformLocation(program, "uBlur")
        stepLocation = GLES20.glGetUniformLocation(program, "uStep")
    }

    private companion object {
        const val MAX_PIXELS = 16_777_216L
        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vUv = aUv;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uMask;
            uniform int uBlur;
            uniform vec2 uStep;
            varying vec2 vUv;
            void main() {
                float visibility = texture2D(uMask, vUv).a;
                if (uBlur != 0) {
                    visibility = visibility * 0.5
                        + texture2D(uMask, vUv + vec2(uStep.x, 0.0)).a * 0.125
                        + texture2D(uMask, vUv - vec2(uStep.x, 0.0)).a * 0.125
                        + texture2D(uMask, vUv + vec2(0.0, uStep.y)).a * 0.125
                        + texture2D(uMask, vUv - vec2(0.0, uStep.y)).a * 0.125;
                }
                gl_FragColor = vec4(0.0, 0.0, 0.0, visibility);
            }
        """
    }
}
