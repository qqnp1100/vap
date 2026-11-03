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

import com.tencent.qgame.animplayer.mask.MaskConfig
import com.tencent.qgame.animplayer.util.ALog
import org.json.JSONException
import org.json.JSONObject

/**
 * vapc里读取出来的基础配置
 */
class AnimConfig {

    companion object {
        private const val TAG = "${Constant.TAG}.AnimConfig"
        private const val MAX_DIMENSION = 16384
        private const val MAX_VIDEO_PIXELS = 64L * 1024 * 1024
        private const val MAX_RESOURCE_PIXELS = 16L * 1024 * 1024
        private const val MAX_RESOURCES = 1024
        private const val MAX_FRAMES = 1000000
        private const val MAX_FRAME_OBJECTS = 100000
    }

    val version = 2 // 不同版本号不兼容
    var totalFrames = 0 // 总帧数
    var width = 0 // 需要显示视频的真实宽高
    var height = 0
    var videoWidth = 0 // 视频实际宽高
    var videoHeight = 0
    var orien = Constant.ORIEN_DEFAULT // 0-兼容模式 1-竖屏 2-横屏
    var fps = 0
    var isMix = false // 是否为融合动画
    var alphaPointRect = PointRect(0, 0 ,0 ,0) // alpha区域
    var rgbPointRect = PointRect(0, 0, 0, 0) // rgb区域
    var isDefaultConfig = false // 没有vapc配置时默认逻辑
    var defaultVideoMode = Constant.VIDEO_MODE_SPLIT_HORIZONTAL

    var maskConfig: MaskConfig ?= null
    var jsonConfig: JSONObject? = null


    /**
     * @return 解析是否成功，失败按默认配置走
     */
    fun parse(json: JSONObject): Boolean {
        return try {
            json.getJSONObject("info").apply {
                val v = getInt("v")
                if (version != v) {
                    ALog.e(TAG, "current version=$version target=$v")
                    return false
                }
                totalFrames = getInt("f")
                width = getInt("w")
                height = getInt("h")
                videoWidth = getInt("videoW")
                videoHeight = getInt("videoH")
                orien = getInt("orien")
                fps = getInt("fps")
                isMix = getInt("isVapx") == 1
                val a = getJSONArray("aFrame") ?: return false
                alphaPointRect = PointRect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
                val c = getJSONArray("rgbFrame") ?: return false
                rgbPointRect = PointRect(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            }
            validate(json)
        } catch (e : JSONException) {
            ALog.e(TAG, "json parse fail $e", e)
            false
        }
    }

    private fun validate(json: JSONObject): Boolean {
        if (!validSize(width, height, MAX_VIDEO_PIXELS) || !validSize(videoWidth, videoHeight, MAX_VIDEO_PIXELS) ||
            totalFrames !in 1..MAX_FRAMES || fps !in 1..240 ||
            !insideVideo(alphaPointRect) || !insideVideo(rgbPointRect)) {
            ALog.e(TAG, "Invalid video dimensions, frame count, fps or packed rectangles")
            return false
        }
        if (!isMix) return true
        val sources = json.getJSONArray("src")
        val frames = json.getJSONArray("frame")
        if (sources.length() > MAX_RESOURCES || frames.length() > totalFrames) return false
        val ids = HashSet<String>()
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            if (!ids.add(source.getString("srcId")) ||
                !validSize(source.getInt("w"), source.getInt("h"), MAX_RESOURCE_PIXELS)) return false
        }
        var objectCount = 0L
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            if (frame.getInt("i") !in 0 until totalFrames) return false
            objectCount += frame.getJSONArray("obj").length()
            if (objectCount > MAX_FRAME_OBJECTS) return false
        }
        return true
    }

    private fun validSize(w: Int, h: Int, maxPixels: Long): Boolean =
        w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION && w.toLong() * h <= maxPixels

    private fun insideVideo(rect: PointRect): Boolean =
        rect.x >= 0 && rect.y >= 0 && rect.w > 0 && rect.h > 0 &&
            rect.x.toLong() + rect.w <= videoWidth && rect.y.toLong() + rect.h <= videoHeight

    override fun toString(): String {
        return "AnimConfig(version=$version, totalFrames=$totalFrames, width=$width, height=$height, videoWidth=$videoWidth, videoHeight=$videoHeight, orien=$orien, fps=$fps, isMix=$isMix, alphaPointRect=$alphaPointRect, rgbPointRect=$rgbPointRect, isDefaultConfig=$isDefaultConfig)"
    }


}


data class PointRect(val x: Int, val y: Int, val w: Int, val h: Int)
data class RefVec2(val w: Int, val h: Int) //参考宽&高
