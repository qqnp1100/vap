package com.tencent.qgame.vap.core.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VapEngineTest {
    @Test
    fun completionWaitsForAudioBeforeClosingSourceAndNotifyingHost() {
        val host = TestHost()
        val engine = VapEngine(host)
        engine.begin("clip", resume = false)
        engine.startPreparedSource("clip", engine.sessionId)
        host.events.clear()

        engine.complete(engine.sessionId) { host.events += "destroyed" }
        engine.complete(engine.sessionId)

        assertFalse(engine.isSessionActive(engine.sessionId))
        assertTrue(engine.isRunning())
        assertEquals(listOf("stop audio", "await idle"), host.events)

        checkNotNull(host.audioIdle).invoke()

        assertFalse(engine.isRunning())
        assertEquals(
            listOf("stop audio", "await idle", "close clip", "before complete", "closed", "complete", "destroyed"),
            host.events
        )
    }

    @Test
    fun stoppingPreparationInvalidatesItsDeferredOutputEvenAfterNewSessionStarts() {
        val host = TestHost().apply { output = false }
        val engine = VapEngine(host)
        engine.begin("first", resume = false)
        val firstId = engine.sessionId
        engine.startPreparedSource("first", firstId)
        val oldStart = checkNotNull(host.deferredStart)
        engine.stop()
        assertFalse(engine.isSessionActive(firstId))
        assertEquals(null, host.deferredStart)
        checkNotNull(host.preparationCleanup).invoke()
        checkNotNull(host.audioIdle).invoke()

        host.output = true
        engine.begin("second", resume = false)
        val secondId = engine.sessionId
        oldStart.run()
        assertFalse(host.events.contains("start first"))
        assertTrue(engine.isSessionActive(secondId))
        engine.startPreparedSource("second", secondId)
        assertTrue(host.events.contains("start second"))
    }

    private class TestHost : VapEngineHost<String> {
        val events = mutableListOf<String>()
        var output = true
        var deferredStart: Runnable? = null
        var preparationCleanup: (() -> Unit)? = null
        var audioIdle: (() -> Unit)? = null

        override fun canBegin() = true
        override fun prepareWorkers() = Unit
        override fun prepareSource(source: String, id: Long) = Unit
        override fun updateWorkerLoops(loops: Int) = Unit
        override fun hasOutput() = output
        override fun awaitOutput(start: Runnable) { deferredStart = start }
        override fun clearOutputWait() { deferredStart = null }
        override fun startWorkers(source: String) { events += "start $source" }
        override fun stopWorkers() = Unit
        override fun stopAudio() { events += "stop audio" }
        override fun cancelResources(id: Long) = Unit
        override fun postPreparationCleanup(cleanup: () -> Unit) { preparationCleanup = cleanup }
        override fun releasePreparedResources() = Unit
        override fun whenAudioIdle(finish: () -> Unit) {
            events += "await idle"
            audioIdle = finish
        }
        override fun closeSource(source: String) { events += "close $source" }
        override fun onBeforeComplete() { events += "before complete" }
        override fun onSessionClosed() { events += "closed" }
        override fun onComplete() { events += "complete" }
        override fun onFailure(error: Int) = Unit
    }
}
