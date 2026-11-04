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

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import com.tencent.qgame.vap.core.internal.codec.SurfaceAudioControls
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Integration hooks for the legacy audio worker. Versioned with both host libraries. */
interface AudioPlaybackHost {
    val workerHandler: Handler?

    fun createWorker(): Boolean
    fun shouldReleaseWorker(): Boolean
    fun quitWorker()
    fun selectAudioTrack(extractor: MediaExtractor): Int
    fun checkSupportCodec(mime: String): Boolean
    fun logInfo(message: String)
    fun logError(message: String, error: Throwable? = null)
    fun logCallbackError(message: String, error: Throwable)
}

/** Owns the audio extractor, codec and output track for one playback instance. */
class AudioPlayback(
    private val host: AudioPlaybackHost,
    private val lock: Any = Any()
) {
    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    var extractor: MediaExtractor? = null
    var decoder: MediaCodec? = null
    var audioTrack: AudioTrack? = null
    internal var surfaceControls: SurfaceAudioControls? = null
    @Volatile var isRunning = false
    private val loopsRemaining = AtomicInteger()
    var playLoop: Int
        get() = loopsRemaining.get()
        set(value) { loopsRemaining.set(value) }
    @Volatile var isStopReq = false
    @Volatile var needDestroy = false
    @Volatile private var generation = 0L
    private class IdleAction(val action: () -> Unit) {
        val completed = AtomicBoolean()
    }
    private val idleActions = ArrayList<IdleAction>()

    fun start(openExtractor: () -> MediaExtractor, sessionIsActive: () -> Boolean) = synchronized(lock) {
        val session = ++generation
        isStopReq = false
        needDestroy = false
        if (!host.createWorker()) {
            surfaceControls?.onFailure(IllegalStateException("Audio worker unavailable"))
            return@synchronized
        }
        isRunning = true
        val accepted = host.workerHandler?.post {
            try {
                if (isActive(session, sessionIsActive)) startPlay(openExtractor, session, sessionIsActive)
            } catch (e: Throwable) {
                host.logError("Audio exception=$e", e)
                if (isActive(session, sessionIsActive)) surfaceControls?.onFailure(e)
            } finally {
                release(session)
            }
        } == true
        if (!accepted) {
            isRunning = false
            surfaceControls?.onFailure(IllegalStateException("Audio worker rejected playback"))
        }
    }

    fun stop() {
        isStopReq = true
        surfaceControls?.wake()
    }

    private fun isActive(session: Long, sessionIsActive: () -> Boolean): Boolean =
        session == generation && !isStopReq && sessionIsActive()

    private fun startPlay(openExtractor: () -> MediaExtractor, session: Long, sessionIsActive: () -> Boolean) {
        val extractor = openExtractor()
        this.extractor = extractor
        val audioIndex = host.selectAudioTrack(extractor)
        if (audioIndex < 0) {
            host.logInfo("cannot find audio track")
            return
        }
        extractor.selectTrack(audioIndex)
        val format = extractor.getTrackFormat(audioIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (!host.checkSupportCodec(mime)) {
            host.logError("mime=$mime not support")
            surfaceControls?.onFailure(IllegalArgumentException("Unsupported audio codec: $mime"))
            return
        }
        if (!isActive(session, sessionIsActive)) return

        // Retain ownership before configure/start, either of which can fail.
        val decoder = MediaCodec.createDecoderByType(mime)
        this.decoder = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        var inputBuffers = decoder.inputBuffers
        var outputBuffers = decoder.outputBuffers
        val bufferInfo = MediaCodec.BufferInfo()
        val controls = surfaceControls
        val active = { isActive(session, sessionIsActive) }
        var pcm = ByteArray(0)
        var inputEOS = false
        var wrotePcm = false
        while (isActive(session, sessionIsActive)) {
            if (controls != null && !controls.awaitPlayback(audioTrack, active)) break
            if (!inputEOS) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = if (Build.VERSION.SDK_INT >= 21) {
                        decoder.getInputBuffer(inputIndex)
                    } else inputBuffers[inputIndex]
                    checkNotNull(inputBuffer) { "Missing audio input buffer" }
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        inputEOS = true
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // A finite wait also prevents spinning after input EOS.
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputBuffers = decoder.outputBuffers
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> replaceAudioTrack(decoder.outputFormat)
            }
            if (outputIndex < 0) continue
            val outputEOS = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            try {
                if (bufferInfo.size > 0 && isActive(session, sessionIsActive)) {
                    val outputBuffer = if (Build.VERSION.SDK_INT >= 21) {
                        decoder.getOutputBuffer(outputIndex)
                    } else outputBuffers[outputIndex]
                    checkNotNull(outputBuffer) { "Missing audio output buffer" }
                    val end = bufferInfo.offset.toLong() + bufferInfo.size
                    require(bufferInfo.offset >= 0 && bufferInfo.size >= 0 && end <= outputBuffer.capacity()) {
                        "Invalid audio output range"
                    }
                    outputBuffer.limit(end.toInt())
                    outputBuffer.position(bufferInfo.offset)
                    if (pcm.size < bufferInfo.size) pcm = ByteArray(bufferInfo.size)
                    outputBuffer.get(pcm, 0, bufferInfo.size)
                    if (audioTrack == null) replaceAudioTrack(decoder.outputFormat)
                    val track = checkNotNull(audioTrack)
                    if (controls?.beginPcm(bufferInfo.presentationTimeUs, active) == false) break
                    var written = 0
                    while (written < bufferInfo.size && isActive(session, sessionIsActive)) {
                        val count = if (controls == null) {
                            track.write(pcm, written, bufferInfo.size - written).also {
                                check(it > 0) { "AudioTrack write failed: $it" }
                            }
                        } else {
                            if (!controls.awaitPlayback(track, active)) break
                            val size = controls.write(track, pcm, written, bufferInfo.size - written)
                            check(size >= 0) { "AudioTrack write failed: $size" }
                            if (size == 0) {
                                controls.waitForOutput()
                                continue
                            }
                            size
                        }
                        if (!wrotePcm) {
                            wrotePcm = true
                            host.logInfo("AudioTrack first PCM write")
                        }
                        written += count
                    }
                }
            } finally {
                decoder.releaseOutputBuffer(outputIndex, false)
            }
            if (outputEOS) {
                if (loopsRemaining.decrementAndGet() <= 0) {
                    controls?.drain(audioTrack, active)
                    break
                }
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                decoder.flush()
                inputBuffers = decoder.inputBuffers
                outputBuffers = decoder.outputBuffers
                inputEOS = false
            }
        }
    }

    private fun replaceAudioTrack(format: MediaFormat) {
        val oldTrack = audioTrack
        audioTrack = null
        releaseAudioTrack(oldTrack)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelConfig = getChannelConfig(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val encoding = if (Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_8BIT) {
            "Unsupported PCM encoding: $encoding"
        }
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(bufferSize > 0) { "Invalid audio buffer size: $bufferSize" }
        val controls = surfaceControls
        val track = if (controls != null) {
            controls.createTrack(sampleRate, channelConfig, encoding, bufferSize)
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, encoding, bufferSize, AudioTrack.MODE_STREAM)
        }
        audioTrack = track
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
        if (controls == null) track.play() else controls.prepareTrack(track, channelConfig, encoding)
    }

    private fun cleanup(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            host.logError("release $name exception=$e", e)
        }
    }

    private fun releaseAudioTrack(track: AudioTrack?) {
        if (track == null) return
        cleanup("AudioTrack.pause") { track.pause() }
        cleanup("AudioTrack.flush") { track.flush() }
        cleanup("AudioTrack.stop") { track.stop() }
        cleanup("AudioTrack.release") { track.release() }
    }

    // Called only by the audio thread, with each resource isolated from other failures.
    private fun release(session: Long) {
        val codec = decoder
        decoder = null
        val source = extractor
        extractor = null
        val track = audioTrack
        audioTrack = null
        surfaceControls?.clearTrack()
        if (codec != null) {
            cleanup("MediaCodec.stop") { codec.stop() }
            cleanup("MediaCodec.release") { codec.release() }
        }
        cleanup("MediaExtractor") { source?.release() }
        releaseAudioTrack(track)
        synchronized(lock) {
            if (session == generation) {
                isRunning = false
                if (needDestroy) destroyInner()
            }
        }
    }

    fun destroy() {
        needDestroy = true
        stop()
        if (!isRunning) destroyInner()
    }

    /** Run after the audio worker has released its current decoder and source. */
    fun releaseWhenIdle(action: () -> Unit) {
        val pending = IdleAction(action)
        synchronized(lock) {
            val handler = host.workerHandler
            if (isRunning && handler != null) {
                idleActions.add(pending)
                if (handler.post { completeIdleAction(pending) }) return
                idleActions.remove(pending)
            }
        }
        completeIdleAction(pending)
    }

    private fun completeIdleAction(pending: IdleAction) {
        synchronized(lock) { idleActions.remove(pending) }
        if (pending.completed.compareAndSet(false, true)) {
            try {
                pending.action()
            } catch (error: Throwable) {
                host.logCallbackError("audio idle resource release", error)
            }
        }
    }

    private fun destroyInner() {
        if (host.shouldReleaseWorker()) {
            host.logInfo("destroyThread")
            // quit() on API 16 drops queued callbacks; finish registered ownership first.
            val pending = synchronized(lock) { idleActions.toList() }
            pending.forEach { completeIdleAction(it) }
            host.workerHandler?.removeCallbacksAndMessages(null)
            host.quitWorker()
        }
    }

    private fun getChannelConfig(channelCount: Int): Int {
        return when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            else -> throw IllegalArgumentException("Unsupported channel count: $channelCount")
        }
    }
}
