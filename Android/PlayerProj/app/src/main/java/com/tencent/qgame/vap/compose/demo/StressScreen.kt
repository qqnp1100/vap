package com.tencent.qgame.vap.compose.demo

import android.os.Debug
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tencent.qgame.vap.compose.VapAnimation
import com.tencent.qgame.vap.compose.VapEvent
import com.tencent.qgame.vap.compose.VapPlaybackPhase
import com.tencent.qgame.vap.compose.rememberVapPlaybackState
import com.tencent.qgame.vap.core.model.VapSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val STRESS_TAG = "VAP-Compose-Stress"

@Composable
internal fun StressScreen(cycles: Int) {
    var visible by remember { mutableStateOf(false) }
    val events = remember { StressEvents() }
    val state = rememberVapPlaybackState()
    val source = remember { VapSource.Asset("demo.mp4") }

    Box(Modifier.fillMaxSize().background(Color(0xFF223250))) {
        if (visible) {
            VapAnimation(
                source = source,
                modifier = Modifier.matchParentSize(),
                state = state,
                audioEnabled = false,
                onEvent = events::accept,
            )
        }
    }

    LaunchedEffect(cycles) {
        var iteration = 0
        Log.i(STRESS_TAG, "SUITE started cycles=$cycles")
        try {
            logSample("baseline", 0)
            repeat(cycles) { index ->
                iteration = index + 1
                visible = true
                withTimeout(8_000) {
                    events.counts.first { it.starts >= iteration || it.completes != 0 || it.failures != 0 }
                }
                events.assertExpected(iteration)
                delay(40)
                visible = false
                // Let the composition remove the host before sampling a detached generation.
                withFrameNanos { }
                withFrameNanos { }
                delay(100)
                events.assertExpected(iteration)
                check(state.snapshot.value.phase == VapPlaybackPhase.Idle) {
                    "Disposed playback state was still bound at cycle $iteration"
                }
                logSample("cycle", iteration)
            }
            val detachedAt = SystemClock.elapsedRealtime()
            for (elapsed in listOf(1_000L, 5_000L, 15_000L)) {
                delay((elapsed - (SystemClock.elapsedRealtime() - detachedAt)).coerceAtLeast(0))
                val workers = logSample("settled", cycles, SystemClock.elapsedRealtime() - detachedAt)
                if (elapsed == 15_000L) check(workers == 0) { "Playback workers remain after 15 seconds: $workers" }
                events.assertExpected(cycles)
            }
            Log.i(STRESS_TAG, "SUITE passed cycles=$cycles ${events.counts.value}")
        } catch (error: TimeoutCancellationException) {
            Log.e(STRESS_TAG, "SUITE failed cycle=$iteration timeout ${events.counts.value}", error)
        } catch (error: IllegalStateException) {
            Log.e(STRESS_TAG, "SUITE failed cycle=$iteration ${events.counts.value}", error)
        } finally {
            visible = false
        }
    }
}

private class StressEvents {
    data class Counts(val starts: Int = 0, val completes: Int = 0, val failures: Int = 0)

    val counts = MutableStateFlow(Counts())
    private var lastStartedRequest = 0L

    fun accept(event: VapEvent) {
        val previous = counts.value
        counts.value = when (event) {
            is VapEvent.Started -> if (event.requestId <= lastStartedRequest) {
                Log.e(STRESS_TAG, "Unexpected Started requestId=${event.requestId} previous=$lastStartedRequest")
                previous.copy(failures = previous.failures + 1)
            } else {
                lastStartedRequest = event.requestId
                previous.copy(starts = previous.starts + 1)
            }
            is VapEvent.Completed -> previous.copy(completes = previous.completes + 1)
            is VapEvent.Failed -> {
                Log.e(STRESS_TAG, "Playback failure kind=${event.error.kind} stage=${event.error.stage}", event.cause)
                previous.copy(failures = previous.failures + 1)
            }
            is VapEvent.ResourceClick -> previous
        }
    }

    fun assertExpected(expectedStarts: Int) {
        check(counts.value == Counts(starts = expectedStarts)) {
            "Expected $expectedStarts starts and no completion or failure, got ${counts.value}"
        }
    }
}

private suspend fun logSample(sample: String, cycle: Int, elapsedMs: Long = 0) = withContext(Dispatchers.IO) {
    val fdDirectory = File("/proc/self/fd")
    val fds = fdDirectory.list()
    var unreadable = 0
    val syncFiles = fds?.count { fd ->
        try {
            val target = Os.readlink(File(fdDirectory, fd).path)
            target == "anon_inode:sync_file" || target == "anon_inode:[sync_file]"
        } catch (_: ErrnoException) {
            unreadable++
            false
        }
    }
    val threads = File("/proc/self/task").listFiles()
    val workers = threads?.count { thread ->
        runCatching { File(thread, "comm").readText().startsWith("vap_surface_") }.getOrDefault(false)
    }
    val pss = Debug.getPss()
    Log.i(
        STRESS_TAG,
        "SAMPLE sample=$sample cycle=$cycle elapsedMs=$elapsedMs " +
            "fdListReadable=${fds != null} fds=${fds?.size ?: -1} syncFiles=${syncFiles ?: -1} " +
            "unreadable=$unreadable threads=${threads?.size ?: -1} workers=${workers ?: -1} pssKiB=$pss"
    )
    check(fds != null && threads != null) { "Process FD or task directory could not be listed" }
    checkNotNull(workers)
}
