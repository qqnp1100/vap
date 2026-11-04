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

import android.os.SystemClock
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.animplayer.file.VapcBoxReader
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.vap.core.spi.decodeVapConfig
import com.tencent.qgame.vap.core.spi.splitLegacyVideo

/**
 * 配置管理
 */
class AnimConfigManager(val player: AnimPlayer) {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimConfigManager"
    }

    var config: AnimConfig? = null
    @Volatile var isParsingConfig = false // 是否正在读取配置

    /**
     * 解析配置
     * @return true 解析成功 false 解析失败
     */
    fun parseConfig(fileContainer: IFileContainer, enableVersion1: Boolean, defaultVideoMode: Int, defaultFps: Int): Int {
        try {
            isParsingConfig = true
            // 解析vapc
            val time = SystemClock.elapsedRealtime()
            val result = parse(fileContainer, defaultVideoMode, defaultFps)
            ALog.i(TAG, "parseConfig cost=${SystemClock.elapsedRealtime() - time}ms enableVersion1=$enableVersion1 result=$result")
            if (!result) {
                isParsingConfig = false
                return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
            }
            if (config?.isDefaultConfig == true && !enableVersion1) {
                isParsingConfig = false
                return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
            }
            return Constant.OK
        } catch (e : Throwable) {
            ALog.e(TAG, "parseConfig error $e", e)
            return Constant.REPORT_ERROR_TYPE_PARSE_CONFIG
        } finally {
            isParsingConfig = false
        }
    }

    /**
     * 默认配置解析（兼容老视频格式）
     */
    fun defaultConfig(_videoWidth: Int, _videoHeight: Int) {
        if (config?.isDefaultConfig == false) return
        config?.apply {
            val layout = splitLegacyVideo(
                _videoWidth,
                _videoHeight,
                vertical = defaultVideoMode == Constant.VIDEO_MODE_SPLIT_VERTICAL ||
                    defaultVideoMode == Constant.VIDEO_MODE_SPLIT_VERTICAL_REVERSE,
                rgbFirst = defaultVideoMode == Constant.VIDEO_MODE_SPLIT_HORIZONTAL_REVERSE ||
                    defaultVideoMode == Constant.VIDEO_MODE_SPLIT_VERTICAL_REVERSE
            )
            videoWidth = layout.videoWidth
            videoHeight = layout.videoHeight
            width = layout.width
            height = layout.height
            alphaPointRect = layout.alpha.toPointRect()
            rgbPointRect = layout.rgb.toPointRect()
        }
    }


    private fun parse(fileContainer: IFileContainer, defaultVideoMode: Int, defaultFps: Int): Boolean {

        val config = AnimConfig()
        this.config = config


        val vapcBuf = VapcBoxReader.read(fileContainer)
        if (vapcBuf == null) {
            ALog.e(TAG, "vapc box head not found")
            // 按照默认配置生成config
            config.apply {
                isDefaultConfig = true
                this.defaultVideoMode = defaultVideoMode
                fps = defaultFps
            }
            player.fps = config.fps
            return true
        }

        val jsonObj = decodeVapConfig(vapcBuf)
        config.jsonConfig = jsonObj
        val result = config.parse(jsonObj)
        if (defaultFps > 0) {
            config.fps = defaultFps
        }
        player.fps = config.fps
        return result
    }

}
