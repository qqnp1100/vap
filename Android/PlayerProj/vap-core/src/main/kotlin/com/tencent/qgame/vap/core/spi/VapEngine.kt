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
package com.tencent.qgame.vap.core.spi

import com.tencent.qgame.vap.core.internal.engine.PlaybackSessionCoordinator
import com.tencent.qgame.vap.core.internal.engine.SessionAdmission

/** Host and worker integration for the shared playback session, versioned with the host libraries. */
interface VapEngineHost<S> {
    fun canBegin(): Boolean
    fun prepareWorkers()
    fun prepareSource(source: S, id: Long)
    fun updateWorkerLoops(loops: Int)
    fun hasOutput(): Boolean
    fun awaitOutput(start: Runnable)
    fun clearOutputWait()
    fun startWorkers(source: S)
    fun stopWorkers()
    fun stopAudio()
    fun cancelResources(id: Long)
    fun postPreparationCleanup(cleanup: () -> Unit)
    fun releasePreparedResources()
    fun whenAudioIdle(finish: () -> Unit)
    fun closeSource(source: S)
    fun onBeforeComplete()
    fun onSessionClosed()
    fun onComplete()
    fun onFailure(error: Int)
}

/** Legacy View policy for preparation, worker control and callback delivery. */
class VapEngine<S : Any>(private val host: VapEngineHost<S>) {
    private val sessions = PlaybackSessionCoordinator<S>(SessionAdmission.RejectWhileBusy)
    val sessionId: Long get() = sessions.lastId
    @Volatile var isPreparing = false
    @Volatile private var remainingVideoLoops = 0

    var playLoop: Int = 0
        set(value) {
            synchronized(sessions.lock) {
                field = value
                remainingVideoLoops = value
                host.updateWorkerLoops(value)
            }
        }

    fun isCurrentSession(id: Long) = sessions.current(id) != null

    fun isSessionActive(id: Long) = sessions.current(id)?.lifetime?.isActive == true

    fun isRunning() = sessions.isRunning()

    fun remainingLoops() = remainingVideoLoops

    fun begin(source: S, resume: Boolean) {
        val id: Long
        synchronized(sessions.lock) {
            if (sessions.isRunning() || !host.canBegin()) return
            if (sessions.select { source } == null) return
            id = checkNotNull(sessions.takeNext()).id
            isPreparing = true
            if (!resume) remainingVideoLoops = playLoop
            host.prepareWorkers()
        }
        host.prepareSource(source, id)
    }

    fun startPreparedSource(source: S, id: Long) {
        synchronized(sessions.lock) {
            if (!isSessionActive(id)) return
            if (host.hasOutput()) {
                if (!sessions.markReady(id)) return
                isPreparing = false
                host.updateWorkerLoops(remainingVideoLoops)
                host.startWorkers(source)
            } else {
                host.awaitOutput(Runnable { startPreparedSource(source, id) })
            }
        }
    }

    fun failPreparation(id: Long, error: Int) {
        if (!isCurrentSession(id)) return
        host.onFailure(error)
        finishPreparation(id)
    }

    fun finishPreparation(id: Long) {
        if (!isCurrentSession(id)) return
        host.releasePreparedResources()
        complete(id)
    }

    fun stop() {
        val id: Long
        val preparing: Boolean
        synchronized(sessions.lock) {
            id = sessionId
            sessions.cancelRunning(id)
            preparing = sessions.isRunning() && isPreparing
            host.clearOutputWait()
            host.stopWorkers()
        }
        host.cancelResources(id)
        if (preparing) host.postPreparationCleanup { finishPreparation(id) }
    }

    fun complete(id: Long, onDestroyed: (() -> Unit)? = null) {
        // Audio may still be reading from the source after video reaches EOS.
        sessions.finish(
            id,
            beforeIdle = {
                host.clearOutputWait()
                host.stopAudio()
            },
            whenIdle = host::whenAudioIdle,
            close = { source ->
                host.closeSource(source)
                host.onBeforeComplete()
                isPreparing = false
                host.onSessionClosed()
            },
            onTerminal = {
                host.onComplete()
                onDestroyed?.invoke()
            },
        )
    }

    /** The legacy decoder exposes a mutable worker loop count. Read and decrement it atomically. */
    fun consumeVideoLoop(decrement: () -> Int): Int = synchronized(sessions.lock) {
        decrement().also { remainingVideoLoops = it }
    }
}
