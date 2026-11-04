package com.tencent.qgame.vap.core.internal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionCoordinatorTest {
    @Test
    fun rejectBusyUntilCancelledSessionClosesItsSource() {
        val coordinator = PlaybackSessionCoordinator<String>(SessionAdmission.RejectWhileBusy)
        val first = checkNotNull(coordinator.select { "first" }).session
        assertSame(first, coordinator.takeNext())
        assertTrue(coordinator.markReady(first.id))
        coordinator.cancelRunning(first.id)
        assertFalse(first.lifetime.isActive)
        assertNull(coordinator.select { "rejected" })

        lateinit var audioIdle: () -> Unit
        val events = mutableListOf<String>()
        coordinator.finish(
            first.id,
            beforeIdle = { events.add("stop audio") },
            whenIdle = { audioIdle = it },
            close = { events.add("close $it") },
            onTerminal = { events.add("complete") },
        )
        assertTrue(coordinator.isRunning())
        assertNull(coordinator.select { "still rejected" })
        assertEquals(listOf("stop audio"), events)

        audioIdle()
        assertEquals(listOf("stop audio", "close first", "complete"), events)
        assertFalse(coordinator.isRunning())
        val second = checkNotNull(coordinator.select { "second" }).session
        assertTrue(second.id > first.id)
        assertSame(second, coordinator.takeNext())
    }

    @Test
    fun replaceOnlyStartsLatestAfterPriorAudioAndSourceAreReleased() {
        val coordinator = PlaybackSessionCoordinator<String>(SessionAdmission.ReplaceLatest)
        val first = checkNotNull(coordinator.select { "first" }).session
        assertSame(first, coordinator.takeNext())
        lateinit var audioIdle: () -> Unit
        coordinator.finish(
            first.id,
            whenIdle = { audioIdle = it },
            close = {
                assertEquals("first", it)
                assertNull(coordinator.takeNext())
            },
            onTerminal = {},
        )

        val second = checkNotNull(coordinator.select { "second" })
        assertSame(first, second.superseded)
        val third = checkNotNull(coordinator.select { "third" })
        assertSame(second.session, third.superseded)
        assertTrue(second.session.lifetime.cancelled)
        assertNull(coordinator.selected(second.session.id))
        assertNull(coordinator.takeNext())
        assertNull(coordinator.withdraw(second.session.id))

        audioIdle()
        assertSame(third.session, coordinator.takeNext())
        assertTrue(third.session.lifetime.isActive)
    }

    @Test
    fun duplicateAndStaleTerminalCallbacksCannotRetireANewerSession() {
        val coordinator = PlaybackSessionCoordinator<String>(SessionAdmission.ReplaceLatest)
        val first = checkNotNull(coordinator.select { "first" }).session
        coordinator.takeNext()
        val events = mutableListOf<String>()
        lateinit var audioIdle: () -> Unit
        coordinator.finish(
            first.id,
            whenIdle = { audioIdle = it },
            close = { events.add("close $it") },
            onTerminal = { events.add("complete") },
        )
        audioIdle()
        val second = checkNotNull(coordinator.select { "second" }).session
        coordinator.takeNext()

        audioIdle()
        coordinator.finish(
            first.id,
            whenIdle = { error("Stale completion must not wait for audio") },
            close = { error("Stale completion must not close the new source") },
            onTerminal = { error("Stale completion must not notify the host") },
        )
        assertEquals(listOf("close first", "complete"), events)
        assertSame(second, coordinator.current(second.id))
    }
}
