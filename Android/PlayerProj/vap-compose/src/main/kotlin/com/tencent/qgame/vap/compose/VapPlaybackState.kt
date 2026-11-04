package com.tencent.qgame.vap.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VapPlaybackPhase {
    Idle, AwaitingSurface, Preparing, Playing, Paused, Suspended, Completed, Failed
}

data class VapPlaybackSnapshot(
    val requestId: Long = 0,
    val phase: VapPlaybackPhase = VapPlaybackPhase.Idle,
    val completedIterations: Int = 0,
    val error: VapPlaybackError? = null,
) {
    val cause: Throwable? get() = error?.cause
}

/** Read-only playback observations. A state can be bound to one host at a time. */
class VapPlaybackState {
    private val mutableSnapshot = MutableStateFlow(VapPlaybackSnapshot())
    val snapshot: StateFlow<VapPlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private var owner: Any? = null

    internal fun bind(host: Any): Boolean {
        if (owner != null && owner !== host) return false
        owner = host
        return true
    }

    internal fun update(host: Any, value: VapPlaybackSnapshot) {
        if (owner === host) mutableSnapshot.value = value
    }

    internal fun unbind(host: Any) {
        if (owner === host) {
            owner = null
            mutableSnapshot.value = VapPlaybackSnapshot()
        }
    }
}

@Composable
fun rememberVapPlaybackState(): VapPlaybackState = remember { VapPlaybackState() }
