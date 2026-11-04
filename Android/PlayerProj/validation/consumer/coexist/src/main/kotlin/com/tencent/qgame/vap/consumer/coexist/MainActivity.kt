package com.tencent.qgame.vap.consumer.coexist

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.vap.compose.VapAnimation
import com.tencent.qgame.vap.compose.VapEvent
import com.tencent.qgame.vap.compose.VapPlaybackPhase
import com.tencent.qgame.vap.compose.VapPlaybackState
import com.tencent.qgame.vap.consumer.FixtureResult
import com.tencent.qgame.vap.core.model.VapSource
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class MainActivity : ComponentActivity() {
    private lateinit var layout: LinearLayout
    private lateinit var animation: AnimView
    private lateinit var result: FixtureResult
    private val composeState = VapPlaybackState()
    private var source by mutableStateOf<String?>(null)
    private var viewStarts by mutableIntStateOf(0)
    private var viewCompletes by mutableIntStateOf(0)
    private var composeStarts by mutableIntStateOf(0)
    private var composeCompletes by mutableIntStateOf(0)
    private var failures by mutableIntStateOf(0)
    private val viewFrames = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        result = FixtureResult(filesDir, TAG)
        val verify = intent.getStringExtra("scenario") == "coexist"
        animation = AnimView(this).apply {
            setBackgroundColor(Color.rgb(49, 73, 107))
            setMute(true)
            setAnimListener(viewListener)
        }
        val composeView = ComposeView(this).apply {
            setBackgroundColor(Color.rgb(67, 95, 83))
            setContent {
                source?.let { name ->
                    VapAnimation(VapSource.Asset(name), state = composeState, audioEnabled = false, onEvent = ::onComposeEvent)
                }
                LaunchedEffect(Unit) {
                    if (verify) runScenario()
                    else {
                        this@MainActivity.animation.startPlay(assets, "demo.mp4")
                        source = "demo.mp4"
                    }
                }
            }
        }
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(this@MainActivity.animation, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(composeView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(layout)
    }

    override fun onStop() {
        animation.stopPlay()
        super.onStop()
    }

    private val viewListener = object : IAnimListener {
        override fun onVideoStart() = runOnUiThread {
            viewStarts++
            result.note("VIEW started=$viewStarts")
        }

        override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
            viewFrames.incrementAndGet()
        }

        override fun onVideoComplete() = runOnUiThread {
            viewCompletes++
            result.note("VIEW completed=$viewCompletes")
        }

        override fun onVideoDestroy() = Unit

        override fun onFailed(errorType: Int, errorMsg: String?) = runOnUiThread {
            failures++
            result.fail("VIEW failed code=$errorType message=$errorMsg", null)
        }
    }

    private fun onComposeEvent(event: VapEvent) {
        when (event) {
            is VapEvent.Started -> {
                composeStarts++
                result.note("COMPOSE started=$composeStarts request=${event.requestId}")
            }
            is VapEvent.Completed -> {
                composeCompletes++
                result.note("COMPOSE completed=$composeCompletes request=${event.requestId}")
            }
            is VapEvent.Failed -> {
                failures++
                result.fail("COMPOSE failed kind=${event.error.kind}", event.cause)
            }
            is VapEvent.ResourceClick -> Unit
        }
    }

    private suspend fun runScenario() {
        result.note("SUITE started")
        try {
            animation.startPlay(assets, "demo.mp4")
            source = "vapx.mp4"
            await { viewStarts == 1 && composeStarts == 1 }

            animation.stopPlay()
            await { viewCompletes == 1 }
            check(!animation.isRunning() && composeStarts == 1 && composeCompletes == 0 && composePlaying())
            animation.startPlay(assets, "vapx.mp4")
            await { viewStarts == 2 }
            check(composeStarts == 1 && composePlaying())
            result.note("PASS view-stop-and-source-change")

            source = null
            awaitComposeIdle()
            val before = viewFrames.get()
            delay(250)
            check(animation.isRunning() && viewStarts == 2 && viewFrames.get() > before)
            check(composeStarts == 1 && composeCompletes == 0)
            result.note("PASS compose-stop-with-view-decoding")

            source = "vapx.mp4"
            await { composeStarts == 2 }
            source = "demo.mp4"
            await { composeStarts == 3 }
            check(viewStarts == 2 && animation.isRunning() && composeCompletes == 0)
            result.note("PASS compose-source-change")

            layout.removeView(animation)
            await { viewCompletes == 2 }
            check(composePlaying())
            await { composeCompletes == 1 }
            check(composeStarts == 3 && viewStarts == 2 && failures == 0)
            source = null
            awaitComposeIdle()
            result.note("SUITE passed viewStarts=$viewStarts composeStarts=$composeStarts composeCompletes=$composeCompletes failures=$failures")
        } catch (error: TimeoutCancellationException) {
            result.fail("SUITE failed timeout viewStarts=$viewStarts composeStarts=$composeStarts", error)
        } catch (error: IllegalStateException) {
            result.fail("SUITE failed", error)
        }
    }

    private fun composePlaying() = composeState.snapshot.value.phase == VapPlaybackPhase.Playing

    private suspend fun awaitComposeIdle() {
        withTimeout(8_000) { composeState.snapshot.first { it.phase == VapPlaybackPhase.Idle } }
        check(failures == 0) { "Playback failed" }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(8_000) { snapshotFlow { failures > 0 || condition() }.first { it } }
        check(failures == 0) { "Playback failed" }
    }

    private companion object { const val TAG = "VapMavenCoexist" }
}
