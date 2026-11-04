package com.tencent.qgame.vap.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VapPlaybackStateTest {
    @Test
    fun onlyBoundHostCanPublishAndAReleasedStateCanBeReused() {
        val state = VapPlaybackState()
        val first = Any()
        val second = Any()
        val playing = VapPlaybackSnapshot(1, VapPlaybackPhase.Playing)

        assertTrue(state.bind(first))
        assertFalse(state.bind(second))
        state.update(first, playing)
        state.update(second, VapPlaybackSnapshot(2, VapPlaybackPhase.Failed))
        state.unbind(second)
        assertEquals(playing, state.snapshot.value)

        state.unbind(first)
        assertEquals(VapPlaybackSnapshot(), state.snapshot.value)
        assertTrue(state.bind(second))
        state.update(first, playing)
        assertEquals(VapPlaybackSnapshot(), state.snapshot.value)
    }
}
