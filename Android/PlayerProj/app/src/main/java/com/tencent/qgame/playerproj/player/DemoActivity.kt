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
package com.tencent.qgame.playerproj.player

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.qgame.animplayer.AnimView
import java.util.concurrent.Future

/** Owns demo file work and accepts its result only while this page is resumed. */
abstract class DemoActivity : Activity() {
    protected lateinit var animView: AnimView
    protected val uiHandler = Handler(Looper.getMainLooper())
    @Volatile protected var isDemoActive = false
        private set
    private var requestId = 0
    private var fileTask: Future<*>? = null
    private var pendingPlayback: Pair<String, String>? = null

    protected abstract fun onDemoResume()

    override fun onResume() {
        super.onResume()
        isDemoActive = true
        onDemoResume()
    }

    protected fun playFile(name: String, md5: String, waitForStop: Boolean = false) {
        if (!isDemoActive || isFinishing) return
        if (animView.isRunning()) {
            if (waitForStop) pendingPlayback = name to md5
            return
        }
        pendingPlayback = null
        fileTask?.cancel(true)
        val request = ++requestId
        val directory = getExternalFilesDir(null) ?: filesDir
        try {
            fileTask = FileUtil.prepareAsset(applicationContext, directory, name, md5, { file ->
                uiHandler.post {
                    if (isDemoActive && request == requestId && !isFinishing) animView.startPlay(file)
                }
            }, { error -> Log.e(javaClass.simpleName, "Cannot prepare $name", error) })
        } catch (e: Exception) {
            Log.e(javaClass.simpleName, "Cannot queue $name", e)
        }
    }

    protected fun stopDemoPlayback() {
        ++requestId
        pendingPlayback = null
        fileTask?.cancel(true)
        fileTask = null
        if (::animView.isInitialized) animView.stopPlay()
    }

    protected fun onDemoPlaybackComplete() {
        uiHandler.post {
            val (name, md5) = pendingPlayback ?: return@post
            playFile(name, md5, waitForStop = true)
        }
    }

    override fun onPause() {
        isDemoActive = false
        stopDemoPlayback()
        uiHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        stopDemoPlayback()
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
