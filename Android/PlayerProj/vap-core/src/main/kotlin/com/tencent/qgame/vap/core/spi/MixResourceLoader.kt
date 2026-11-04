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

import android.graphics.Bitmap
import android.os.Handler
import java.util.IdentityHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** VAPX resource scheduling shared by the player adapters. Call [load] on the render handler. */
class MixResourceLoader {
    sealed interface Request {
        class Image(
            val fetch: ((Bitmap?) -> Unit) -> Unit,
            val accept: (Bitmap?) -> Unit,
            val discard: (Bitmap) -> Unit
        ) : Request

        class Text(
            val fetch: ((String?) -> Unit) -> Unit,
            val accept: (String) -> Unit
        ) : Request

        object Invalid : Request
    }

    private val generation = AtomicLong()
    private val lock = Any()
    private var session = 0L
    private var timeout: Runnable? = null
    private var timeoutHandler: Handler? = null
    private val pendingImages = HashSet<() -> Unit>()

    fun load(
        session: Long,
        handler: Handler,
        requests: List<Request>,
        timeoutMs: Long,
        isSessionActive: (Long) -> Boolean,
        prepareBitmaps: () -> Boolean,
        onError: (Throwable) -> Unit,
        complete: (Boolean) -> Unit
    ) = load(
        session, handler, requests, timeoutMs, Int.MAX_VALUE, false, isSessionActive, prepareBitmaps, onError, complete
    )

    internal fun load(
        session: Long,
        handler: Handler,
        requests: List<Request>,
        timeoutMs: Long,
        maxConcurrent: Int,
        reportTimeout: Boolean,
        isSessionActive: (Long) -> Boolean,
        prepareBitmaps: () -> Boolean,
        onError: (Throwable) -> Unit,
        complete: (Boolean) -> Unit
    ) {
        if (requests.isEmpty()) {
            complete(true)
            return
        }
        val loadId = synchronized(lock) {
            this.session = session
            generation.incrementAndGet()
        }
        var remaining = requests.size
        var finished = false
        var inFlight = 0
        var nextRequest = 0
        lateinit var startMore: () -> Unit
        fun current() = loadId == generation.get() && isSessionActive(session)
        fun finish(success: Boolean) {
            if (finished || !current()) return
            finished = true
            synchronized(lock) {
                timeout?.let(handler::removeCallbacks)
                timeout = null
                timeoutHandler = null
            }
            // Invalidate callbacks arriving after failure or timeout.
            if (!success) cancel(session)
            complete(success)
        }
        fun received(valid: Boolean) {
            if (!current() || finished) return
            if (!valid) {
                finish(false)
                return
            }
            remaining--
            inFlight--
            if (remaining == 0) finish(prepareBitmaps())
            else startMore()
        }
        synchronized(lock) {
            if (!current()) return
            timeoutHandler = handler
            timeout = Runnable {
                if (!finished && current()) {
                    if (reportTimeout) onError(TimeoutException("VAPX resource loading exceeded ${timeoutMs}ms"))
                    finish(false)
                }
            }.also {
                handler.postDelayed(it, timeoutMs.coerceAtLeast(1L))
            }
        }
        fun start(request: Request) {
            val once = AtomicBoolean()
            try {
                when (request) {
                    is Request.Image -> {
                        val seenBitmaps = IdentityHashMap<Bitmap, Boolean>()
                        request.fetch callback@{ bitmap ->
                            // A duplicate carrying the same object must not recycle an accepted image.
                            val firstObject = bitmap == null || synchronized(seenBitmaps) {
                                seenBitmaps.put(bitmap, true) == null
                            }
                            if (!firstObject) return@callback
                            val first = once.compareAndSet(false, true)
                            val delivered = AtomicBoolean()
                            lateinit var discard: () -> Unit
                            discard = {
                                if (delivered.compareAndSet(false, true)) {
                                    synchronized(lock) { pendingImages.remove(discard) }
                                    bitmap?.let(request.discard)
                                }
                            }
                            val enqueue = synchronized(lock) {
                                if (first && current()) {
                                    pendingImages.add(discard)
                                    true
                                } else false
                            }
                            if (!enqueue) {
                                discard()
                            } else if (!handler.post {
                                if (delivered.compareAndSet(false, true)) {
                                    synchronized(lock) { pendingImages.remove(discard) }
                                    if (!current() || finished) {
                                        bitmap?.let(request.discard)
                                    } else {
                                        request.accept(bitmap)
                                        received(bitmap == null || !bitmap.isRecycled)
                                    }
                                }
                            }) {
                                discard()
                            }
                        }
                    }
                    is Request.Text -> request.fetch { text ->
                        if (once.compareAndSet(false, true)) handler.post {
                            if (current() && !finished) {
                                request.accept(text.orEmpty())
                                received(true)
                            }
                        }
                    }
                    Request.Invalid -> received(false)
                }
            } catch (error: Throwable) {
                onError(error)
                finish(false)
            }
        }
        startMore = {
            while (!finished && current() && inFlight < maxConcurrent && nextRequest < requests.size) {
                inFlight++
                start(requests[nextRequest++])
            }
        }
        startMore()
    }

    fun cancel(expectedSession: Long? = null) {
        val pending = synchronized(lock) {
            if (expectedSession != null && session != expectedSession) return
            generation.incrementAndGet()
            timeout?.let { timeoutHandler?.removeCallbacks(it) }
            timeout = null
            timeoutHandler = null
            pendingImages.toList().also { pendingImages.clear() }
        }
        pending.forEach { it() }
    }
}
