package com.tencent.qgame.vap.compose

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LifecycleStartEffect
import com.tencent.qgame.vap.core.model.VapSource

sealed interface VapEvent {
    val requestId: Long

    /** The first frame was successfully submitted to the display Surface. */
    data class Started(override val requestId: Long) : VapEvent

    data class Completed(override val requestId: Long) : VapEvent

    data class Failed(override val requestId: Long, val error: VapPlaybackError) : VapEvent {
        val cause: Throwable get() = error.cause
    }

    data class ResourceClick(
        override val requestId: Long,
        val sourceId: String,
        val tag: String,
    ) : VapEvent
}

/**
 * Plays a VAP v2 clip on an embedded Compose Surface.
 *
 * Changing [playbackKey] restarts the clip. A clip interrupted by the lifecycle or a lost
 * Surface restarts from the beginning when the host becomes available again.
 */
@Composable
fun VapAnimation(
    source: VapSource,
    modifier: Modifier = Modifier,
    state: VapPlaybackState = rememberVapPlaybackState(),
    playbackKey: Long = 0L,
    isPlaying: Boolean = true,
    isActive: Boolean = true,
    iterations: Int = 1,
    audioEnabled: Boolean = true,
    isMuted: Boolean = true,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    resourceProvider: VapResourceProvider? = null,
    resourceKey: Long = 0L,
    mask: VapMask? = null,
    enableResourceClicks: Boolean = false,
    onEvent: (VapEvent) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val layoutDirection = LocalLayoutDirection.current
    val latestEvent = rememberUpdatedState(onEvent)
    val host = remember(context, state) { SurfaceHost(context, state) { latestEvent.value(it) } }

    DisposableEffect(host) {
        host.bind()
        onDispose { host.dispose() }
    }
    LifecycleStartEffect(host) {
        host.setStarted(true)
        onStopOrDispose { host.setStarted(false) }
    }
    SideEffect {
        host.updateLayout(contentScale, alignment, layoutDirection)
        host.updateRequest(
            source, playbackKey, iterations, audioEnabled, resourceKey, resourceProvider,
            isPlaying, isActive, isMuted, mask,
        )
    }

    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            if (isActive) {
                key(host) {
                    val input = if (enableResourceClicks) Modifier.pointerInput(host) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val up = waitForUpOrCancellation()
                            if (up != null && host.click(up.position.x, up.position.y)) up.consume()
                        }
                    } else Modifier
                    AndroidEmbeddedExternalSurface(modifier = input, isOpaque = false) {
                        onSurface { surface, width, height ->
                            val output = SurfaceHost.Output(surface, width, height)
                            surface.onChanged { w, h -> host.resize(output, w, h) }
                            surface.onDestroyed { host.destroy(output) }
                            host.attach(output)
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val video = host.videoSize
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else video.width.coerceAtLeast(maxOf(constraints.minWidth, 1))
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight
            else video.height.coerceAtLeast(maxOf(constraints.minHeight, 1))
        if (measurables.isEmpty()) return@Layout layout(width, height) {}
        val child = measurables.single().measure(
            Constraints.fitPrioritizingWidth(
                minWidth = width,
                maxWidth = width,
                minHeight = height,
                maxHeight = height,
            ),
        )
        layout(width, height) { child.place(0, 0) }
    }
}
