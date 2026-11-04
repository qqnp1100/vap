package com.tencent.qgame.vap.compose.demo

import android.graphics.Bitmap
import android.net.Uri

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.qgame.vap.compose.VapAnimation
import com.tencent.qgame.vap.compose.VapEvent
import com.tencent.qgame.vap.compose.VapErrorKind
import com.tencent.qgame.vap.compose.VapErrorStage
import com.tencent.qgame.vap.compose.VapImageLease
import com.tencent.qgame.vap.compose.VapMask
import com.tencent.qgame.vap.compose.VapPlaybackPhase
import com.tencent.qgame.vap.compose.VapResourceProvider
import com.tencent.qgame.vap.compose.VapResourceRequest
import com.tencent.qgame.vap.compose.rememberVapPlaybackState
import com.tencent.qgame.vap.core.model.VapSource
import com.tencent.qgame.vap.core.spi.SourceNotSeekableException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val TAG = "VAP-Compose-Demo"

@Composable
internal fun DemoScreen(scenario: String?, cycles: Int, moveToBackground: () -> Unit) {
    val verifySurface = scenario == "surface"
    val verifyLegacy = scenario == "legacy"
    val verifyControls = scenario == "controls"
    val verifyVapx = scenario == "vapx"
    val verifyUri = scenario == "uri"
    val verifyLifecycle = scenario == "lifecycle"
    val verifyMask = scenario == "mask"
    val verifyTimeout = scenario == "resource-timeout"
    val verifyInvalid = scenario == "invalid-request"
    val verifyAudioLength = scenario == "audio-short" || scenario == "audio-long"
    val automated = verifySurface || verifyLegacy || verifyControls || verifyVapx || verifyUri ||
        verifyLifecycle || verifyMask || verifyTimeout || verifyInvalid || verifyAudioLength
    val context = LocalContext.current
    val cacheDir = context.cacheDir
    var uriPath by remember { mutableStateOf("sample") }
    var legacy by remember { mutableStateOf(verifyLegacy) }
    var vapx by remember { mutableStateOf(verifyVapx || verifyTimeout) }
    var visible by remember { mutableStateOf(true) }
    var compact by remember { mutableStateOf(false) }
    var transformed by remember { mutableStateOf(false) }
    var cropped by remember { mutableStateOf(false) }
    var recomposeTick by remember { mutableIntStateOf(0) }
    var playbackKey by remember { mutableLongStateOf(0) }
    var starts by remember { mutableIntStateOf(0) }
    var completes by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("等待首帧") }
    val playbackState = rememberVapPlaybackState()
    var playing by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(!verifyVapx && !verifyAudioLength) }
    var iterations by remember {
        mutableIntStateOf(when {
            verifyInvalid -> 0
            verifyControls || verifyLifecycle -> 2
            else -> 1
        })
    }
    var mask by remember { mutableStateOf<VapMask?>(null) }
    val maskSample = remember {
        VapMask(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in width / 2 until width) setPixel(x, y, -1)
        })
    }
    val images = remember { AtomicInteger() }
    val texts = remember { AtomicInteger() }
    val releases = remember { AtomicInteger() }
    val cancellations = remember { AtomicInteger() }
    val resources = remember(verifyTimeout) {
        object : VapResourceProvider {
            override suspend fun loadImage(request: VapResourceRequest): VapImageLease {
                images.incrementAndGet()
                if (verifyTimeout) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancellations.incrementAndGet()
                    }
                }
                val bitmap = Bitmap.createBitmap(
                    request.targetWidth.coerceIn(1, 384), request.targetHeight.coerceIn(1, 384),
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(0xFF4A79DF.toInt()) }
                return VapImageLease(bitmap) {
                    bitmap.recycle()
                    releases.incrementAndGet()
                }
            }

            override suspend fun loadText(request: VapResourceRequest): String {
                texts.incrementAndGet()
                return "VAP Compose"
            }
        }
    }

    LaunchedEffect(scenario, cycles) {
        if (!automated) return@LaunchedEffect
        Log.i(TAG, "M2 SUITE STARTED scenario=$scenario cycles=$cycles")
        suspend fun awaitStart(expected: Int) {
            withTimeout(8_000) {
                snapshotFlow { starts >= expected || failures > 0 }.first { it }
            }
            check(failures == 0) { "playback failures=$failures" }
            check(starts == expected) { "expected starts=$expected, got $starts" }
        }
        try {
            if (verifyLegacy) {
                withTimeout(8_000) { snapshotFlow { failures > 0 || starts > 0 }.first { it } }
                check(failures == 1 && starts == 0 && completes == 0) {
                    "expected strict V2 rejection, starts=$starts completes=$completes failures=$failures"
                }
                check(playbackState.snapshot.value.error?.kind == VapErrorKind.InvalidVapConfig)
                status = "V1 素材已拒绝"
                Log.i(TAG, "M2 SUITE PASSED scenario=legacy starts=$starts completes=$completes failures=$failures")
                return@LaunchedEffect
            }
            if (verifyInvalid || verifyTimeout) {
                val rejected = withTimeout(14_000) {
                    playbackState.snapshot.first { it.phase == VapPlaybackPhase.Failed }
                }
                val kind = if (verifyInvalid) VapErrorKind.InvalidRequest else VapErrorKind.ResourceTimeout
                val stage = if (verifyInvalid) VapErrorStage.Request else VapErrorStage.Resource
                check(rejected.error?.kind == kind && rejected.error?.stage == stage)
                if (verifyTimeout) {
                    delay(100)
                    check(images.get() == 1 && cancellations.get() == 1 && releases.get() == 0)
                }
                check(starts == 0 && completes == 0 && failures == 1)
                Log.i(TAG, "M3 SUITE PASSED scenario=$scenario kind=$kind stage=$stage cancellations=${cancellations.get()}")
                return@LaunchedEffect
            }
            if (verifyLifecycle) {
                awaitStart(1)
                val first = withTimeout(8_000) { playbackState.snapshot.first { it.completedIterations == 1 } }
                delay(250)
                moveToBackground()
                val suspended = withTimeout(5_000) {
                    playbackState.snapshot.first { it.phase == VapPlaybackPhase.Suspended }
                }
                check(suspended.requestId == first.requestId && suspended.completedIterations == 1)
                Log.i(TAG, "M3 lifecycle suspended requestId=${first.requestId} completed=1")
                awaitStart(2)
                val complete = withTimeout(8_000) {
                    playbackState.snapshot.first { it.phase == VapPlaybackPhase.Completed }
                }
                check(complete.requestId == first.requestId && complete.completedIterations == 2 &&
                    starts == 2 && completes == 1 && failures == 0)
                Log.i(TAG, "M3 SUITE PASSED scenario=lifecycle requestId=${complete.requestId} completed=2 starts=2")
                return@LaunchedEffect
            }
            if (verifyMask) {
                awaitStart(1)
                delay(650)
                playing = false
                withTimeout(2_000) { playbackState.snapshot.first { it.phase == VapPlaybackPhase.Paused } }
                delay(250)
                Log.i(TAG, "M3 mask screenshot=reference")
                delay(1700)
                mask = maskSample
                delay(200)
                Log.i(TAG, "M3 mask screenshot=applied")
                delay(1700)
                mask = null
                delay(200)
                Log.i(TAG, "M3 mask screenshot=removed")
                delay(1700)
                check(starts == 1 && completes == 0 && failures == 0)
                Log.i(TAG, "M3 SUITE PASSED scenario=mask paused=true")
                return@LaunchedEffect
            }
            if (verifyAudioLength) {
                val requestedAt = SystemClock.elapsedRealtime()
                awaitStart(1)
                withTimeout(8_000) { snapshotFlow { completes > 0 || failures > 0 }.first { it } }
                val elapsed = SystemClock.elapsedRealtime() - requestedAt
                check(completes == 1 && starts == 1 && failures == 0 && elapsed in 2800..5500)
                Log.i(TAG, "M3 SUITE PASSED scenario=$scenario elapsedMs=$elapsed")
                return@LaunchedEffect
            }
            if (verifyControls) {
                awaitStart(1)
                playing = false
                withTimeout(2_000) { playbackState.snapshot.first { it.phase == VapPlaybackPhase.Paused } }
                mask = maskSample
                delay(250)
                mask = null
                delay(250)
                check(starts == 1 && completes == 0 && failures == 0)
                playing = true
                withTimeout(12_000) { playbackState.snapshot.first { it.phase == VapPlaybackPhase.Completed } }
                check(playbackState.snapshot.value.completedIterations == 2 && starts == 1 && completes == 1)
                iterations = -1
                awaitStart(2)
                withTimeout(12_000) { playbackState.snapshot.first { it.completedIterations >= 2 } }
                check(completes == 1 && failures == 0)
                visible = false
                status = "播放控制验证通过"
                Log.i(TAG, "M3 SUITE PASSED scenario=controls pause=1 maskChanges=2 finite=2 infiniteObserved=2")
                return@LaunchedEffect
            }
            if (verifyVapx) {
                awaitStart(1)
                delay(550)
                playing = false
                delay(350)
                playing = true
                delay(500)
                muted = true
                withTimeout(15_000) { snapshotFlow { completes > 0 || failures > 0 }.first { it } }
                check(failures == 0 && completes == 1 && starts == 1 &&
                    images.get() > 0 && texts.get() > 0 && releases.get() == images.get()) {
                    "VAPX starts=$starts completes=$completes images=${images.get()} texts=${texts.get()} releases=${releases.get()}"
                }
                status = "VAPX 验证通过"
                Log.i(TAG, "M3 SUITE PASSED scenario=vapx images=${images.get()} texts=${texts.get()} releases=${releases.get()}")
                return@LaunchedEffect
            }
            if (verifyUri) {
                awaitStart(1)
                withTimeout(8_000) { snapshotFlow { completes > 0 || failures > 0 }.first { it } }
                check(completes == 1 && failures == 0)
                suspend fun rejectUri(path: String, kind: VapErrorKind): Throwable? {
                    val previousRequest = playbackState.snapshot.value.requestId
                    uriPath = path
                    val rejected = withTimeout(8_000) {
                        playbackState.snapshot.first {
                            it.requestId != previousRequest && it.phase == VapPlaybackPhase.Failed
                        }
                    }
                    check(rejected.error?.kind == kind && rejected.error?.stage == VapErrorStage.Source)
                    return rejected.cause
                }
                check(rejectUri("pipe", VapErrorKind.SourceNotSeekable) is SourceNotSeekableException)
                check(rejectUri("missing", VapErrorKind.SourceAccess) is java.io.FileNotFoundException)
                check(rejectUri("denied", VapErrorKind.SourceAccess) is SecurityException)
                check(starts == 1 && completes == 1)
                status = "content URI 验证通过"
                Log.i(TAG, "M3 SUITE PASSED scenario=uri seekable=1 nonSeekableRejected=1 sourceAccessRejected=2")
                return@LaunchedEffect
            }
            awaitStart(1)
            repeat(100) { index ->
                withFrameNanos { }
                recomposeTick++
                if (index == 24 || index == 49) compact = !compact
                if (index == 74) {
                    transformed = true
                    cropped = true
                }
            }
            withFrameNanos { }
            check(starts == 1 && failures == 0) {
                "recomposition or resize restarted playback, starts=$starts failures=$failures"
            }
            Log.i(TAG, "M2 PASS recompose=100 resize=2 transform=1 starts=$starts")

            repeat(cycles) { index ->
                visible = false
                delay(120)
                visible = true
                awaitStart(index + 2)
            }
            Log.i(TAG, "M2 PASS surface-recreate cycles=$cycles starts=$starts")

            val beforeComplete = completes
            playbackKey++
            awaitStart(cycles + 2)
            withTimeout(8_000) { snapshotFlow { completes > beforeComplete || failures > 0 }.first { it } }
            check(failures == 0 && starts == cycles + 2)
            status = "Surface 验证通过"
            Log.i(TAG, "M2 SUITE PASSED cycles=$cycles starts=$starts completes=$completes failures=$failures")
        } catch (error: TimeoutCancellationException) {
            status = "Surface 验证超时"
            Log.e(TAG, "M2 SUITE FAILED timeout starts=$starts completes=$completes failures=$failures", error)
        } catch (error: IllegalStateException) {
            status = "Surface 验证失败"
            Log.e(TAG, "M2 SUITE FAILED starts=$starts completes=$completes failures=$failures", error)
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFFF4F6FA))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BasicText("VAP Compose", style = TextStyle(Color(0xFF152340), 26.sp, fontWeight = FontWeight.Bold))
        DemoText("棋盘在视频下方，右下角标签在视频上方。")
        Box(
            Modifier.fillMaxWidth().height(if (compact) 174.dp else 288.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            Checkerboard(Modifier.matchParentSize())
            if (visible) {
                val frameLabel = "VAP 动画 $recomposeTick"
                VapAnimation(
                    source = if (verifyAudioLength) VapSource.File(File(cacheDir, "${scenario!!.removePrefix("audio-")}-audio.mp4"))
                    else if (verifyUri) VapSource.Uri(Uri.parse(
                        "content://${context.packageName}.samples/$uriPath"
                    )) else VapSource.Asset(when {
                        legacy -> "special_size_750.mp4"
                        vapx -> "vapx.mp4"
                        else -> "demo.mp4"
                    }),
                    modifier = Modifier.matchParentSize()
                        .semantics { contentDescription = frameLabel }
                        .graphicsLayer {
                            rotationZ = if (transformed) -12f else 0f
                            scaleX = if (transformed) 0.88f else 1f
                            scaleY = scaleX
                        },
                    contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit,
                    alignment = Alignment.Center,
                    playbackKey = playbackKey,
                    state = playbackState,
                    isPlaying = playing,
                    iterations = iterations,
                    audioEnabled = vapx || verifyAudioLength,
                    isMuted = muted,
                    resourceProvider = if (vapx) resources else null,
                    enableResourceClicks = vapx,
                    mask = mask,
                    onEvent = { event ->
                        when (event) {
                            is VapEvent.Started -> {
                                starts++
                                status = "正在播放"
                                Log.i(TAG, "M2 first-frame starts=$starts")
                            }
                            is VapEvent.Completed -> {
                                completes++
                                status = "播放完成"
                                Log.i(TAG, "M2 completed completes=$completes")
                            }
                            is VapEvent.Failed -> {
                                failures++
                                status = "播放失败 ${event.cause.message.orEmpty()}"
                                Log.e(TAG, "Playback failed failures=$failures kind=${event.error.kind} stage=${event.error.stage}", event.cause)
                            }
                            is VapEvent.ResourceClick -> {
                                status = "点击 ${event.tag}"
                                Log.i(TAG, "M3 resource-click sourceId=${event.sourceId} tag=${event.tag}")
                            }
                        }
                    }
                )
            }
            BasicText(
                if (visible) "Compose 前景" else "Surface 已移除",
                Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .background(Color(0xED152340), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(Color.White, 13.sp)
            )
        }
        if (!automated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control("重播") { playbackKey++ }
                Control("重组") { recomposeTick++ }
                Control("改尺寸") { compact = !compact }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control(if (visible) "移除" else "显示") { visible = !visible }
                Control(if (cropped) "适配" else "裁剪") { cropped = !cropped }
                Control("旋转") { transformed = !transformed }
            }
            Control(if (legacy) "播放 V2 样片" else "测试 V1 拒绝") { legacy = !legacy }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Control(if (playing) "暂停" else "继续") { playing = !playing }
                Control(if (muted) "开声音" else "静音") { muted = !muted }
                Control(if (mask == null) "加遮罩" else "去遮罩") { mask = if (mask == null) maskSample else null }
            }
            Control(if (vapx) "播放 V2 样片" else "播放 VAPX") { legacy = false; vapx = !vapx }
        }
        DemoText(status)
        DemoText("重组 $recomposeTick · 首帧 $starts · 完成 $completes · 错误 $failures")
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val cell = 20.dp.toPx()
        val columns = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                drawRect(
                    if ((row + column) % 2 == 0) Color(0xFFF3F7FD) else Color(0xFF849CB9),
                    Offset(column * cell, row * cell),
                    Size(cell, cell)
                )
            }
        }
    }
}

@Composable
private fun DemoText(text: String) {
    BasicText(text, style = TextStyle(Color(0xFF334966), 15.sp))
}

@Composable
private fun Control(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        label,
        Modifier.clip(shape).background(Color(0xFFDAE6F6)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(Color(0xFF152340), 15.sp, fontWeight = FontWeight.Medium)
    )
}
