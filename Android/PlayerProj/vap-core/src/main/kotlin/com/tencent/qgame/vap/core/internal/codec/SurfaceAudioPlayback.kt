package com.tencent.qgame.vap.core.internal.codec

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaExtractor
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.tencent.qgame.vap.core.internal.engine.SurfaceVideoClock
import com.tencent.qgame.vap.core.spi.AudioPlayback
import com.tencent.qgame.vap.core.spi.AudioPlaybackHost
import com.tencent.qgame.vap.core.spi.CoreMediaAccess

/** Owns one audio worker and serializes its lifetime against video iteration changes. */
@TargetApi(23)
internal class SurfaceAudioPlayback(
    private val openExtractor: () -> MediaExtractor,
    private val isSessionActive: () -> Boolean,
    private val clock: SurfaceVideoClock,
    private val onFailure: (Throwable) -> Unit,
) : AudioPlaybackHost {
    private val lock = Any()
    private val diagnostics = Log.isLoggable(AV_SYNC_TAG, Log.DEBUG)
    private val controls = SurfaceAudioControls(onFailure, diagnostics, clock)
    private val playback = AudioPlayback(this, lock).apply { surfaceControls = controls }
    private var thread: HandlerThread? = null
    private var trackProbed = false
    private var firstAudioPtsUs: Long? = null
    private var destroyed = false
    private var generation = 0L
    @Volatile private var clockVisible = false
    private var sampledVideoPtsUs: Long? = null

    override var workerHandler: Handler? = null
        private set

    /** Called from the decode worker before either track starts presenting. */
    fun startIteration(firstVideoPtsUs: Long) {
        sampledVideoPtsUs = null
        if (!isSessionActive()) return
        if (!trackProbed) {
            try {
                val probe = openExtractor()
                try {
                    val index = selectAudioTrack(probe)
                    if (index >= 0) {
                        probe.selectTrack(index)
                        firstAudioPtsUs = probe.sampleTime.takeUnless { it == -1L }
                    }
                } finally {
                    probe.release()
                }
            } catch (error: Throwable) {
                if (isSessionActive()) onFailure(error)
                return
            }
            trackProbed = true
        }
        clock.startIteration(firstVideoPtsUs, firstAudioPtsUs)
        if (firstAudioPtsUs == null) return
        synchronized(lock) {
            if (destroyed || !isSessionActive()) return
            clockVisible = false
            val id = ++generation
            playback.releaseWhenIdle {
                synchronized(lock) {
                    if (!destroyed && generation == id && isSessionActive()) {
                        // The previous worker has cleared its position before idle callbacks run.
                        clockVisible = true
                        playback.playLoop = 1
                        playback.start(openExtractor, isSessionActive)
                    }
                }
            }
        }
    }

    fun stopIteration() {
        synchronized(lock) {
            clockVisible = false
            generation++
            playback.stop()
        }
        clock.wake()
    }

    fun setMuted(value: Boolean) = controls.setMuted(value)

    fun setPaused(value: Boolean) = controls.setPaused(value)

    fun positionUs(): Long? = if (clockVisible) controls.positionUs else null

    /** The delta is signed audio played-head PTS minus video submission PTS. */
    fun recordVideoSubmission(videoPtsUs: Long) {
        if (!diagnostics) return
        val last = sampledVideoPtsUs
        if (last != null && videoPtsUs - last < 100_000) return
        val audioPtsUs = positionUs() ?: return
        sampledVideoPtsUs = videoPtsUs
        Log.d(AV_SYNC_TAG, "submission videoPtsUs=$videoPtsUs audioPtsUs=$audioPtsUs deltaUs=${audioPtsUs - videoPtsUs}")
    }

    fun destroy() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            clockVisible = false
            generation++
            playback.destroy()
        }
        clock.wake()
    }

    fun releaseWhenIdle(action: () -> Unit) = playback.releaseWhenIdle(action)

    override fun createWorker(): Boolean {
        if (workerHandler != null) return true
        return try {
            val worker = HandlerThread("vap_surface_audio")
            thread = worker
            worker.start()
            workerHandler = Handler(worker.looper)
            true
        } catch (error: Throwable) {
            logError("Audio worker creation failed", error)
            quitWorker()
            false
        }
    }

    override fun shouldReleaseWorker() = destroyed

    override fun quitWorker() {
        thread?.quitSafely()
        thread = null
        workerHandler = null
    }

    override fun selectAudioTrack(extractor: MediaExtractor): Int =
        CoreMediaAccess.selectTrack(extractor, "audio/", ::logInfo)

    override fun checkSupportCodec(mime: String): Boolean =
        CoreMediaAccess.checkSupportCodec(mime, ::logInfo) { logError("Audio codec discovery failed", it) }

    override fun logInfo(message: String) {
        Log.i(TAG, message)
    }

    override fun logError(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
    }

    override fun logCallbackError(message: String, error: Throwable) = logError(message, error)

    private companion object {
        const val TAG = "VapSurfaceAudio"
    }
}

/** Audio-thread transport state. Caller threads publish playback intent and read its clock. */
private const val AV_SYNC_TAG = "VapAvSync"

@TargetApi(23)
internal class SurfaceAudioControls(
    val onFailure: (Throwable) -> Unit,
    private val diagnostics: Boolean,
    private val clock: SurfaceVideoClock,
) {
    private val signal = Object()
    @Volatile private var muted = true
    @Volatile private var paused = false

    @Volatile var positionUs: Long? = null
        private set

    private var appliedMuted: Boolean? = null
    private var firstPtsUs: Long? = null
    private var bytesPerFrame = 0
    private var sampleRate = 0
    private var resumedAtMs = 0L
    private var writtenBytes = 0L
    private var lastHead = 0L
    private var headWraps = 0L

    fun setMuted(value: Boolean) {
        muted = value
        wake()
    }

    fun setPaused(value: Boolean) {
        paused = value
        wake()
    }

    fun wake() {
        synchronized(signal) { signal.notifyAll() }
    }

    fun createTrack(sampleRate: Int, channelConfig: Int, encoding: Int, bufferSize: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    fun prepareTrack(track: AudioTrack, channelConfig: Int, encoding: Int) {
        clearTrack()
        sampleRate = track.sampleRate
        bytesPerFrame = Integer.bitCount(channelConfig) *
            if (encoding == AudioFormat.ENCODING_PCM_16BIT) 2 else 1
        applyVolume(track)
    }

    fun clearTrack() {
        positionUs = null
        firstPtsUs = null
        writtenBytes = 0
        lastHead = 0
        headWraps = 0
        appliedMuted = null
        resumedAtMs = 0
    }

    fun beginPcm(ptsUs: Long, isActive: () -> Boolean): Boolean {
        if (firstPtsUs == null) {
            // Keep the audio clock unpublished until the shared timeline reaches this PCM.
            clock.awaitPresentation(ptsUs, { !isActive() }) { null }
            if (!isActive()) return false
            firstPtsUs = ptsUs
        }
        return true
    }

    fun awaitPlayback(track: AudioTrack?, isActive: () -> Boolean): Boolean {
        while (isActive()) {
            if (track != null) {
                applyVolume(track)
                if (paused && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    reportTransport("paused")
                }
                if (!paused && firstPtsUs != null && track.playState != AudioTrack.PLAYSTATE_PLAYING) play(track)
                updateClock(track)
            }
            if (!paused) return true
            waitForOutput()
        }
        return false
    }

    fun write(track: AudioTrack, pcm: ByteArray, offset: Int, size: Int): Int {
        val count = track.write(pcm, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        if (count > 0) writtenBytes += count
        updateClock(track)
        return count
    }

    fun drain(track: AudioTrack?, isActive: () -> Boolean) {
        if (track == null || bytesPerFrame == 0) return
        val framesWritten = writtenBytes / bytesPerFrame
        var previous = -1L
        var progressedAtMs = SystemClock.elapsedRealtime()
        while (awaitPlayback(track, isActive)) {
            val head = updateClock(track)
            val now = SystemClock.elapsedRealtime()
            if (head >= framesWritten) return
            if (head != previous) {
                previous = head
                progressedAtMs = now
            } else if (now - maxOf(progressedAtMs, resumedAtMs) >= DRAIN_STALL_MS) {
                return
            }
            waitForOutput()
        }
    }

    fun waitForOutput() {
        synchronized(signal) { signal.wait(POLL_MS) }
    }

    private fun applyVolume(track: AudioTrack) {
        val current = muted
        if (appliedMuted == current) return
        val result = track.setVolume(if (current) 0f else 1f)
        check(result == AudioTrack.SUCCESS) { "AudioTrack volume failed: $result" }
        appliedMuted = current
        if (diagnostics) Log.d(AV_SYNC_TAG, "audio mute=$current")
    }

    private fun play(track: AudioTrack) {
        track.play()
        resumedAtMs = SystemClock.elapsedRealtime()
        reportTransport("playing")
    }

    private fun reportTransport(state: String) {
        if (diagnostics) Log.d(AV_SYNC_TAG, "audio state=$state positionUs=$positionUs")
    }

    private fun updateClock(track: AudioTrack): Long {
        val rawHead = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (lastHead - rawHead > 0x80000000L) headWraps++
        lastHead = rawHead
        val frames = (headWraps shl 32) + rawHead
        firstPtsUs?.let { positionUs = it + frames * 1_000_000 / sampleRate }
        return frames
    }

    private companion object {
        const val POLL_MS = 5L
        const val DRAIN_STALL_MS = 1_000L
    }
}
