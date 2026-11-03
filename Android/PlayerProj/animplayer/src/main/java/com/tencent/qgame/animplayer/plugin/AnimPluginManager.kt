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
package com.tencent.qgame.animplayer.plugin

import android.view.MotionEvent
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimPlayer
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.mask.MaskAnimPlugin
import com.tencent.qgame.animplayer.mix.MixAnimPlugin
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.safeCall

/**
 * 动画插件管理
 */
class AnimPluginManager(val player: AnimPlayer) {
    companion object {
        private const val TAG = "${Constant.TAG}.AnimPluginManager"
    }

    private val mixAnimPlugin = MixAnimPlugin(player)
    private val maskAnimPlugin = MaskAnimPlugin(player)

    private val plugins = listOf<IAnimPlugin>(mixAnimPlugin, maskAnimPlugin)

    fun getMixAnimPlugin(): MixAnimPlugin? {
        return mixAnimPlugin
    }
    
    fun getMaskAnimPlugin() : MaskAnimPlugin? {
        return maskAnimPlugin
    }

    fun onConfigCreate(config: AnimConfig): Int {
        ALog.i(TAG, "onConfigCreate")
        plugins.forEach {
            val res = it.onConfigCreate(config)
            if (res != Constant.OK) {
                return res
            }
        }
        return Constant.OK
    }

    fun onRenderCreate() {
        ALog.i(TAG, "onRenderCreate")
        plugins.forEach {
            it.onRenderCreate()
        }
    }

    fun onDecoding(decodeIndex: Int) {
        plugins.forEach {
            it.onDecoding(decodeIndex)
        }
    }

    internal fun onRendering(decodedFrameIndex: Int) {
        plugins.forEach { it.onRendering(decodedFrameIndex) }
    }

    fun onRelease() {
        ALog.i(TAG, "onRelease")
        plugins.forEach {
            safeCall("plugin onRelease") { it.onRelease() }
        }
    }

    fun onDestroy() {
        ALog.i(TAG, "onDestroy")
        plugins.forEach {
            safeCall("plugin onDestroy") { it.onDestroy() }
        }
    }

    fun onDispatchTouchEvent(ev: MotionEvent): Boolean {
        plugins.forEach {
            if (it.onDispatchTouchEvent(ev)) return true
        }
        return false
    }
}
