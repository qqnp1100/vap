package com.tencent.qgame.playerproj.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.file.AssetsFileContainer
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.mix.Resource
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IALog
import com.tencent.qgame.animplayer.util.ScaleType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only device regression suite. Launch with:
 * adb shell am start -n com.tencent.qgame.playerproj/.player.StabilityTestActivity
 * Optional extras: --ei cycles 20 --es scenario all --ez autorun true --ez audio true
 * Read results using logcat -s VAP-Stability; each case and the suite have explicit results.
 */
class StabilityTestActivity : Activity() {
    companion object { private const val TAG = "VAP-Stability" }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var status: TextView
    private val history = ArrayList<String>()
    private val steps = ArrayList<Pair<String, () -> Unit>>()
    private var stepIndex = 0
    private var passed = 0
    private var failed = 0
    private var suiteToken = 0
    private var suiteStarted = 0L
    private var suiteRunning = false
    @Volatile private var current: Case? = null
    private var reusableView: AnimView? = null
    private var reusableAssets: AssetsFileContainer? = null

    private class Case(val name: String, val view: AnimView, val token: Int) {
        val configs = AtomicInteger()
        val starts = AtomicInteger()
        val renders = AtomicInteger()
        val completes = AtomicInteger()
        val destroysAfterComplete = AtomicInteger()
        val errors = AtomicInteger()
        val audioWrites = AtomicInteger()
        val audioErrors = AtomicInteger()
        val releases = AtomicInteger()
        val zeroFrameCallbacks = AtomicInteger()
        val sessionRenders = AtomicInteger()
        val invalidBitmap = AtomicBoolean()
        val renderActionSent = AtomicBoolean()
        var finished = false // main thread only
        var expectedCompletes = 1
        var stopAtFrame = 3
        var settleMs = 180L
        var onRender: (() -> Unit)? = null
        var validate: () -> String? = { null }
        var deadline: Runnable? = null
        @Volatile var bitmap: Bitmap? = null
        fun counts() = "config=${configs.get()} start=${starts.get()} decodedCallbacks=${renders.get()} " +
            "frameZeroCallbacks=${zeroFrameCallbacks.get()} " +
            "complete=${completes.get()} destroy=${destroysAfterComplete.get()} error=${errors.get()} " +
            "release=${releases.get()} pcmStarts=${audioWrites.get()} audioErrors=${audioErrors.get()} running=${view.isRunning()}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ALog.isDebug = false
        ALog.log = object : IALog {
            override fun i(tag: String, msg: String) {
                if (tag == "${Constant.TAG}.AudioPlayer" && msg == "AudioTrack first PCM write") {
                    current?.audioWrites?.incrementAndGet()
                }
                Log.i(tag, msg)
            }
            override fun d(tag: String, msg: String) { Log.d(tag, msg) }
            override fun e(tag: String, msg: String) {
                if (tag == "${Constant.TAG}.AudioPlayer") current?.audioErrors?.incrementAndGet()
                Log.e(TAG, "LIBRARY $tag $msg")
            }
            override fun e(tag: String, msg: String, tr: Throwable) {
                if (tag == "${Constant.TAG}.AudioPlayer") current?.audioErrors?.incrementAndGet()
                Log.e(TAG, "LIBRARY $tag $msg", tr)
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply {
            text = "Restart stability suite"
            setOnClickListener { startSuite() }
        })
        host = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(12, 12, 12, 12) }
        root.addView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (intent.getBooleanExtra("autorun", true)) main.postDelayed({ startSuite() }, 250)
    }

    private fun startSuite() {
        ++suiteToken
        current?.view?.stopPlay()
        current = null
        host.removeAllViews()
        reusableView = null
        reusableAssets = null
        history.clear()
        File(filesDir, "stability-results.log").writeText("")
        steps.clear()
        stepIndex = 0
        passed = 0
        failed = 0
        suiteRunning = true
        suiteStarted = SystemClock.elapsedRealtime()
        buildSteps()
        val filter = intent.getStringExtra("scenario") ?: "all"
        if (filter != "all") steps.removeAll { !it.first.contains(filter, ignoreCase = true) }
        line("SUITE STARTED cases=${steps.size} filter=$filter")
        if (steps.isEmpty()) {
            failed++
            line("FAIL no scenario matched '$filter'")
        }
        val token = suiteToken
        System.gc()
        main.postDelayed({
            if (suiteRunning && token == suiteToken) {
                metrics("start-after-gc")
                nextStep()
            }
        }, 1_000)
    }

    private fun buildSteps() {
        steps.add("background-start-immediate-stop" to {
            val test = beginCase("background-start-immediate-stop")
            test.expectedCompletes = 0
            test.view.setAnimListener(listener(test))
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread({
                try {
                    test.view.startPlay(assets, "demo.mp4")
                    test.view.stopPlay()
                } catch (error: Throwable) { workerFailure.set(error) }
            }, "stability_start_stop")
            worker.start()
            // Hold this main-thread test action until start and stop are queued in that order.
            // This bounded barrier exercises cancellation before the UI start Runnable can run.
            worker.join(1_000)
            test.validate = {
                expect(!worker.isAlive && workerFailure.get() == null, "background action did not finish: ${workerFailure.get()}")
                    ?: expect(test.configs.get() == 0 && test.starts.get() == 0 && test.renders.get() == 0 && test.view.childCount == 0,
                        "cancelled queued start performed work or created a TextureView")
            }
            postFor(test, 700) { finishCase(test) }
        })
        steps.add("reject-config" to {
            val test = beginCase("reject-config")
            reusableView = test.view
            test.view.setAnimListener(listener(test, rejectConfig = true))
            test.validate = { expect(test.configs.get() == 1 && test.starts.get() == 0 && test.errors.get() == 0,
                "rejected config must complete without decoding") }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("replay-after-reject" to {
            val test = beginCase("replay-after-reject", reuse = reusableView)
            playThenStop(test)
        })
        steps.add("resource-timeout" to {
            val test = beginCase("resource-timeout", timeoutMs = 13_000)
            test.view.setFetchResource(source(test, never = true))
            test.view.setAnimListener(listener(test))
            test.validate = { expect(test.errors.get() > 0 && test.starts.get() == 0,
                "missing resource callbacks must fail and complete within the timeout") }
            test.view.startPlay(assets, "vapx.mp4")
        })
        steps.add("duplicate-resource-callbacks" to {
            val test = beginCase("duplicate-resource-callbacks")
            test.view.setFetchResource(source(test, duplicate = true))
            playThenStop(test, "vapx.mp4") {
                expect(test.releases.get() == 1 && !test.invalidBitmap.get(),
                    "duplicate callback recycled an accepted bitmap or released it more than once")
            }
        })
        steps.add("missing-optional-image" to {
            val test = beginCase("missing-optional-image")
            test.view.setFetchResource(source(test, missingImage = true))
            playThenStop(test, "vapx.mp4") {
                expect(test.releases.get() == 0, "placeholder must remain owned by the player")
            }
        })
        steps.add("stop-loading-late-callback" to {
            val test = beginCase("stop-loading-late-callback")
            test.settleMs = 650
            test.view.setFetchResource(source(test, delayed = true))
            test.view.setAnimListener(listener(test))
            test.validate = { expect(test.starts.get() == 0 && test.releases.get() == 1,
                "cancelled loading must not start; late image must be returned exactly once") }
            test.view.startPlay(assets, "vapx.mp4")
        })
        steps.add("stop-before-layout" to {
            val test = beginCase("stop-before-layout", attach = false)
            test.settleMs = 650
            test.view.setAnimListener(listener(test, afterConfig = {
                postFor(test, 50) { test.view.stopPlay() }
                postFor(test, 200) { host.addView(test.view, fill()) }
            }))
            test.validate = { expect(test.configs.get() == 1 && test.starts.get() == 0 && test.view.childCount == 0,
                "stopping before layout must suppress delayed TextureView creation") }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("throw-config-callback" to {
            val test = beginCase("throw-config-callback")
            test.view.setAnimListener(listener(test, throwConfig = true))
            test.validate = { expect(test.configs.get() == 1 && test.starts.get() == 0,
                "throwing config callback must terminate preparation") }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("throw-render-callback" to {
            val test = beginCase("throw-render-callback")
            test.onRender = { test.view.stopPlay() }
            test.view.setAnimListener(listener(test, throwRender = true))
            test.validate = { played(test) }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("throw-complete-callback" to {
            val test = beginCase("throw-complete-callback")
            test.onRender = { test.view.stopPlay() }
            test.view.setAnimListener(listener(test, throwComplete = true))
            test.validate = { played(test) }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("throw-release-callback" to {
            val test = beginCase("throw-release-callback")
            test.view.setFetchResource(source(test, throwRelease = true))
            playThenStop(test, "vapx.mp4") {
                expect(test.releases.get() == 1, "release callback was not invoked exactly once")
            }
        })
        steps.add("invalid-mp4" to { invalidFileCase(throwFailure = false) })
        steps.add("throw-failure-callback" to { invalidFileCase(throwFailure = true) })
        steps.add("assets-first-use" to {
            val test = beginCase("assets-first-use")
            reusableView = test.view
            reusableAssets = AssetsFileContainer(assets, "demo.mp4")
            playThenStop(test, container = reusableAssets)
        })
        steps.add("assets-reuse-after-close" to {
            val test = beginCase("assets-reuse-after-close", reuse = reusableView)
            playThenStop(test, container = reusableAssets)
        })
        steps.add("rapid-detach-auto-resume" to {
            val test = beginCase("rapid-detach-auto-resume", timeoutMs = 8_000)
            test.expectedCompletes = 2
            test.view.setLoop(2)
            val detached = AtomicBoolean()
            test.onRender = {
                if (detached.compareAndSet(false, true)) {
                    // Intentionally no delay: reattach while the old decoder is still releasing.
                    host.removeView(test.view)
                    host.addView(test.view, fill())
                } else if (test.starts.get() >= 2) test.view.stopPlay()
            }
            test.view.setAnimListener(listener(test))
            test.validate = {
                expect(detached.get() && test.starts.get() == 2 && test.renders.get() >= 6 && test.errors.get() == 0,
                    "rapid reattach did not automatically resume and finish two sessions")
            }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("natural-eos-two-loops" to {
            val test = beginCase("natural-eos-two-loops", timeoutMs = 11_000)
            test.view.setLoop(1)
            test.view.setAnimListener(listener(test, afterConfig = { test.view.setLoop(2) }))
            // demo.mp4 contains 80 frames at 25fps. These are decoder callbacks, not presented FPS.
            test.validate = {
                expect(test.starts.get() == 1 && test.renders.get() >= 160 && test.zeroFrameCallbacks.get() == 2 && test.errors.get() == 0,
                    "two natural EOS loops must decode both cycles, with one start and one completion")
                    ?: expect(test.destroysAfterComplete.get() == 1, "expected one destroy callback after completion")
            }
            test.view.startPlay(assets, "demo.mp4")
        })
        steps.add("prepare-detach-auto-resume" to {
            val test = beginCase("prepare-detach-auto-resume")
            test.expectedCompletes = 2
            test.view.setLoop(1)
            test.onRender = { test.view.stopPlay() }
            test.view.setAnimListener(listener(test, afterConfig = {
                if (test.configs.get() == 1) {
                    val reattached = CountDownLatch(1)
                    postFor(test) {
                        host.removeView(test.view)
                        host.addView(test.view, fill())
                        reattached.countDown()
                    }
                    check(reattached.await(1, TimeUnit.SECONDS))
                }
            }))
            test.validate = { played(test) ?: expect(test.configs.get() == 2,
                "reattach during preparation must start a fresh session") }
            test.view.startPlay(assets, "demo.mp4")
        })
        if (intent.getBooleanExtra("audio", false)) steps.add("audible-vapx-smoke" to {
            val test = beginCase("audible-vapx-smoke", timeoutMs = 6_000)
            test.stopAtFrame = 25
            test.view.setMute(false)
            test.view.setFetchResource(source(test))
            playThenStop(test, "vapx.mp4") {
                expect(test.audioWrites.get() == 1 && test.audioErrors.get() == 0,
                    "expected successful PCM output without audio errors, writes=${test.audioWrites.get()} errors=${test.audioErrors.get()}")
            }
        })
        val cycles = intent.getIntExtra("cycles", 20).coerceIn(1, 100)
        repeat(cycles) { index ->
            val name = "detach-attach-${index + 1}"
            steps.add(name to {
                val test = beginCase(name, reuse = reusableView)
                reusableView = test.view
                test.settleMs = 250
                test.onRender = {
                    test.view.stopPlay()
                    host.removeView(test.view)
                    postFor(test, 100) { host.addView(test.view, fill()) }
                }
                test.view.setAnimListener(listener(test))
                test.validate = { played(test) ?: expect(test.view.parent === host,
                    "view was not reattached after decoder cleanup") }
                test.view.startPlay(assets, "demo.mp4")
            })
        }
    }

    private fun invalidFileCase(throwFailure: Boolean) {
        val test = beginCase(if (throwFailure) "throw-failure-callback" else "invalid-mp4")
        val bad = File(cacheDir, "stability-invalid.mp4")
        // A declared 2 GiB box with only an eight-byte header must fail without a large allocation.
        bad.writeBytes(byteArrayOf(0x7f, -1, -1, -1, 0x76, 0x61, 0x70, 0x63))
        test.view.setAnimListener(listener(test, throwFailure = throwFailure))
        test.validate = { expect(test.errors.get() > 0 && test.starts.get() == 0,
            "invalid box must report failure and release the session") }
        test.view.startPlay(bad)
    }

    private fun beginCase(name: String, reuse: AnimView? = null, attach: Boolean = true,
                          timeoutMs: Long = 5_000): Case {
        val view = reuse ?: AnimView(this).apply {
            setMute(true)
            setLoop(0)
            setScaleType(ScaleType.FIT_CENTER)
        }
        if (host.childCount != 1 || host.getChildAt(0) !== view) {
            host.removeAllViews()
            if (attach) host.addView(view, fill())
        }
        view.setFetchResource(null)
        val test = Case(name, view, suiteToken)
        current = test
        line("RUN $name")
        test.deadline = Runnable { finishCase(test, "case timeout after ${timeoutMs}ms") }.also {
            main.postDelayed(it, timeoutMs)
        }
        return test
    }

    private fun playThenStop(test: Case, asset: String = "demo.mp4", container: AssetsFileContainer? = null,
                             extraCheck: () -> String? = { null }) {
        test.onRender = { test.view.stopPlay() }
        test.view.setAnimListener(listener(test))
        test.validate = { played(test) ?: extraCheck() }
        if (container != null) test.view.startPlay(container) else test.view.startPlay(assets, asset)
    }

    private fun listener(test: Case, rejectConfig: Boolean = false, throwConfig: Boolean = false,
                         throwRender: Boolean = false, throwComplete: Boolean = false,
                         throwFailure: Boolean = false, afterConfig: (() -> Unit)? = null) = object : IAnimListener {
        override fun onVideoConfigReady(config: AnimConfig): Boolean {
            test.configs.incrementAndGet()
            if (throwConfig) throw IllegalStateException("injected config callback failure")
            afterConfig?.invoke()
            return !rejectConfig
        }
        override fun onVideoStart() {
            test.starts.incrementAndGet()
            test.sessionRenders.set(0)
            test.renderActionSent.set(false)
        }
        override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
            test.renders.incrementAndGet()
            val count = test.sessionRenders.incrementAndGet()
            if (frameIndex == 0) test.zeroFrameCallbacks.incrementAndGet()
            if (test.bitmap?.isRecycled == true) test.invalidBitmap.set(true)
            if (count >= test.stopAtFrame && test.renderActionSent.compareAndSet(false, true)) {
                postFor(test) { test.onRender?.invoke() }
            }
            if (throwRender) throw IllegalStateException("injected render callback failure")
        }
        override fun onVideoComplete() {
            val count = test.completes.incrementAndGet()
            if (test.expectedCompletes > 0 && count >= test.expectedCompletes) {
                postFor(test, test.settleMs) { finishCase(test) }
            }
            if (throwComplete) throw IllegalStateException("injected complete callback failure")
        }
        override fun onVideoDestroy() {
            if (test.completes.get() > 0) test.destroysAfterComplete.incrementAndGet()
        }
        override fun onFailed(errorType: Int, errorMsg: String?) {
            test.errors.incrementAndGet()
            Log.i(TAG, "EVENT ${test.name} failed type=$errorType message=$errorMsg")
            if (throwFailure) throw IllegalStateException("injected failure callback failure")
        }
    }

    private fun source(test: Case, never: Boolean = false, duplicate: Boolean = false,
                       delayed: Boolean = false, throwRelease: Boolean = false,
                       missingImage: Boolean = false) = object : IFetchResource {
        override fun fetchImage(resource: Resource, result: (Bitmap?) -> Unit) {
            if (never) return
            if (missingImage) {
                result(null)
                return
            }
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            test.bitmap = bitmap
            if (delayed) {
                postFor(test) { test.view.stopPlay() }
                postFor(test, 350) { result(bitmap) }
            } else {
                result(bitmap)
                if (duplicate) result(bitmap)
            }
        }
        override fun fetchText(resource: Resource, result: (String?) -> Unit) {
            if (never) return
            if (delayed) postFor(test, 350) { result("late") }
            else {
                result("stability")
                if (duplicate) result("duplicate")
            }
        }
        override fun releaseResource(resources: List<Resource>) {
            test.releases.incrementAndGet()
            resources.forEach { it.bitmap?.let { image -> if (!image.isRecycled) image.recycle() } }
            if (throwRelease) throw IllegalStateException("injected release callback failure")
        }
    }

    private fun finishCase(test: Case, forcedFailure: String? = null) {
        if (test.finished || test.token != suiteToken || current !== test) return
        test.finished = true
        test.deadline?.let { main.removeCallbacks(it) }
        val failure = forcedFailure ?: expect(test.completes.get() == test.expectedCompletes,
            "expected ${test.expectedCompletes} completion(s)")
            ?: expect(!test.view.isRunning(), "session still running after completion") ?: test.validate()
        if (failure == null) {
            ++passed
            line("PASS ${test.name} ${test.counts()}")
        } else {
            ++failed
            line("FAIL ${test.name}: $failure; ${test.counts()}")
            test.view.stopPlay()
            if (reusableView === test.view) reusableView = null
        }
        val cycle = test.name.removePrefix("detach-attach-").toIntOrNull()
        if (cycle != null && (cycle == 1 || cycle % 25 == 0)) metrics("cycle-$cycle")
        current = null
        val token = suiteToken
        main.postDelayed({ if (suiteRunning && token == suiteToken) nextStep() }, 40)
    }

    private fun nextStep() {
        if (stepIndex >= steps.size) {
            suiteRunning = false
            host.removeAllViews()
            reusableView = null
            reusableAssets?.close()
            reusableAssets = null
            val token = suiteToken
            main.postDelayed({
                if (token == suiteToken) {
                    System.gc()
                    main.postDelayed({
                        if (token == suiteToken) {
                            metrics("settled-after-gc")
                            line("SUITE FINISHED passed=$passed failed=$failed durationMs=${SystemClock.elapsedRealtime() - suiteStarted}")
                        }
                    }, 1_000)
                }
            }, 250)
            return
        }
        val step = steps[stepIndex++]
        try {
            step.second()
        } catch (error: Throwable) {
            current?.let { finishCase(it, "test setup exception: $error") } ?: run {
                ++failed
                line("FAIL ${step.first}: test setup exception $error")
                main.post { nextStep() }
            }
        }
    }

    private fun played(test: Case): String? = expect(test.starts.get() == 1 && test.renders.get() >= test.stopAtFrame && test.errors.get() == 0,
        "playback did not reach ${test.stopAtFrame} decoder callbacks without an error")
    private fun expect(condition: Boolean, message: String): String? = if (condition) null else message
    private fun postFor(test: Case, delayMs: Long = 0, action: () -> Unit) {
        main.postDelayed({ if (suiteRunning && test.token == suiteToken && !test.finished) action() }, delayMs)
    }
    private fun line(message: String) {
        // Some OEM devices suppress INFO; retain complete results for adb run-as export.
        Log.w(TAG, message)
        File(filesDir, "stability-results.log").appendText(message + "\n")
        history.add(message)
        status.text = history.takeLast(50).joinToString("\n")
    }
    private fun metrics(label: String) {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        line("METRIC $label fd=${File("/proc/self/fd").list()?.size} " +
            "threads=${File("/proc/self/task").list()?.size} " +
            "javaUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} totalPssKiB=${memory.totalPss}")
    }
    private fun fill() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        if (suiteRunning) {
            suiteRunning = false
            ++suiteToken
            line("SUITE ABORTED: activity paused")
        }
        current?.view?.stopPlay()
        host.removeAllViews()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        reusableAssets?.close()
        super.onDestroy()
    }
}
