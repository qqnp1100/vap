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
package com.tencent.qgame.animplayer

import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.MediaUtil
import com.tencent.qgame.vap.core.spi.AudioPlayback
import com.tencent.qgame.vap.core.spi.AudioPlaybackHost

class AudioPlayer(val player: AnimPlayer) {

    companion object {
        private const val TAG = "${Constant.TAG}.AudioPlayer"
    }

    val decodeThread = HandlerHolder(null, null)
    private val playback = AudioPlayback(object : AudioPlaybackHost {
        override val workerHandler get() = decodeThread.handler

        override fun createWorker() = Decoder.createThread(decodeThread, "anim_audio_thread")

        override fun shouldReleaseWorker() = player.isDetachedFromWindow

        override fun quitWorker() {
            decodeThread.thread = Decoder.quitSafely(decodeThread.thread)
            decodeThread.handler = null
        }

        override fun selectAudioTrack(extractor: MediaExtractor) = MediaUtil.selectAudioTrack(extractor)

        override fun checkSupportCodec(mime: String) = MediaUtil.checkSupportCodec(mime)

        override fun logInfo(message: String) = ALog.i(TAG, message)

        override fun logError(message: String, error: Throwable?) {
            if (error == null) ALog.e(TAG, message) else ALog.e(TAG, message, error)
        }

        override fun logCallbackError(message: String, error: Throwable) =
            ALog.e("AnimPlayer.Callback", message, error)
    }, lock = this)

    var extractor: MediaExtractor?
        get() = playback.extractor
        set(value) { playback.extractor = value }

    var decoder: MediaCodec?
        get() = playback.decoder
        set(value) { playback.decoder = value }

    var audioTrack: AudioTrack?
        get() = playback.audioTrack
        set(value) { playback.audioTrack = value }

    var isRunning: Boolean
        get() = playback.isRunning
        set(value) { playback.isRunning = value }

    var playLoop: Int
        get() = playback.playLoop
        set(value) { playback.playLoop = value }

    var isStopReq: Boolean
        get() = playback.isStopReq
        set(value) { playback.isStopReq = value }

    var needDestroy: Boolean
        get() = playback.needDestroy
        set(value) { playback.needDestroy = value }

    @Synchronized
    fun start(fileContainer: IFileContainer) {
        val playerSession = player.sessionId
        playback.start({ MediaUtil.getExtractor(fileContainer) }) {
            player.isSessionActive(playerSession)
        }
    }

    fun stop() = playback.stop()

    fun destroy() = playback.destroy()

    /** Run after the audio worker has released its current decoder and source. */
    fun releaseWhenIdle(action: () -> Unit) = playback.releaseWhenIdle(action)
}
