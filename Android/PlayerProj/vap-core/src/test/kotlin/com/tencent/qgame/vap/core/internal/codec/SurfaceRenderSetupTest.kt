package com.tencent.qgame.vap.core.internal.codec

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SurfaceRenderSetupTest {
    @Test
    fun callerReceivesTheOriginalPackedSizeError() {
        val mismatch = SurfaceMediaConfigException("Packed dimensions do not match the video")
        val reported = assertThrows(SurfaceMediaConfigException::class.java) {
            runSurfaceRenderSetup(post = { task ->
                task.run()
                true
            }) { throw mismatch }
        }
        assertSame(mismatch, reported)
    }
}
