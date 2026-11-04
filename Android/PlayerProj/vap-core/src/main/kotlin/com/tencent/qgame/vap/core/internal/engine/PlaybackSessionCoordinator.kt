package com.tencent.qgame.vap.core.internal.engine

/** Admission differs by host, but a session keeps its worker slot until its source is closed. */
internal enum class SessionAdmission {
    RejectWhileBusy,
    ReplaceLatest,
}

internal class PlaybackSessionCoordinator<S : Any>(private val admission: SessionAdmission) {
    enum class Phase { Queued, Preparing, Ready, Closing, Finalizing, Closed }

    class Lifetime(val id: Long) {
        @Volatile var phase = Phase.Queued
            internal set
        @Volatile var cancelled = false
            internal set

        val isActive: Boolean
            get() = !cancelled && (phase == Phase.Preparing || phase == Phase.Ready)
    }

    data class Entry<S>(val lifetime: Lifetime, val source: S) {
        val id get() = lifetime.id
    }

    data class Selection<S>(val session: Entry<S>, val superseded: Entry<S>?)

    val lock = Any()
    @Volatile var lastId = 0L
        private set
    @Volatile private var running: Entry<S>? = null
    private var selected: Entry<S>? = null

    fun select(create: (Lifetime) -> S): Selection<S>? = synchronized(lock) {
        if (admission == SessionAdmission.RejectWhileBusy && (running != null || selected != null)) return null
        val lifetime = Lifetime(lastId + 1)
        val next = Entry(lifetime, create(lifetime))
        lastId = lifetime.id
        val previous = selected
        previous?.lifetime?.cancelled = true
        selected = next
        Selection(next, previous)
    }

    fun takeNext(): Entry<S>? = synchronized(lock) {
        if (running != null) return null
        val next = selected?.takeUnless { it.lifetime.cancelled } ?: return null
        next.lifetime.phase = Phase.Preparing
        running = next
        next
    }

    fun selected(id: Long): Entry<S>? = synchronized(lock) { selected?.takeIf { it.id == id } }

    fun withdraw(id: Long? = null): Entry<S>? = synchronized(lock) {
        val entry = selected?.takeIf { id == null || it.id == id } ?: return null
        entry.lifetime.cancelled = true
        selected = null
        entry
    }

    fun current(id: Long): Entry<S>? = running?.takeIf { it.id == id }

    fun isRunning(): Boolean = running != null

    fun cancelRunning(id: Long) {
        synchronized(lock) { current(id)?.lifetime?.cancelled = true }
    }

    fun markReady(id: Long): Boolean = synchronized(lock) {
        val lifetime = current(id)?.lifetime ?: return false
        if (lifetime.cancelled || lifetime.phase != Phase.Preparing) return false
        lifetime.phase = Phase.Ready
        true
    }

    /**
     * Begins terminal cleanup once, waits for audio ownership to end, then releases the source
     * before making the worker slot available. A stale or repeated idle callback is ignored.
     */
    fun finish(
        id: Long,
        beforeIdle: () -> Unit = {},
        whenIdle: (() -> Unit) -> Unit,
        close: (S) -> Unit,
        onRetired: (Entry<S>, Boolean) -> Unit = { _, _ -> },
        onTerminal: () -> Unit,
    ) {
        val entry = synchronized(lock) {
            val current = current(id) ?: return
            if (current.lifetime.phase != Phase.Preparing && current.lifetime.phase != Phase.Ready) return
            current.lifetime.phase = Phase.Closing
            beforeIdle()
            current
        }
        whenIdle {
            val claimed = synchronized(lock) {
                if (running !== entry || entry.lifetime.phase != Phase.Closing) false
                else {
                    entry.lifetime.phase = Phase.Finalizing
                    true
                }
            }
            if (claimed) {
                fun closeAndRetire() {
                    try {
                        close(entry.source)
                    } finally {
                        synchronized(lock) {
                            entry.lifetime.phase = Phase.Closed
                            running = null
                            val wasSelected = selected === entry
                            if (wasSelected) selected = null
                            onRetired(entry, wasSelected)
                        }
                    }
                }
                if (admission == SessionAdmission.RejectWhileBusy) synchronized(lock) { closeAndRetire() }
                else closeAndRetire()
                onTerminal()
            }
        }
    }
}
