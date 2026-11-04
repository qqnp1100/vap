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
package com.tencent.qgame.vap.core.internal.config

import com.tencent.qgame.vap.core.spi.VAP_CONFIG_VERSION
import com.tencent.qgame.vap.core.spi.VapConfigFields
import com.tencent.qgame.vap.core.spi.VapConfigParseError
import com.tencent.qgame.vap.core.spi.VideoRect
import org.json.JSONException
import org.json.JSONObject

internal object VapConfigParser {
    private const val MAX_DIMENSION = 16384
    private const val MAX_VIDEO_PIXELS = 64L * 1024 * 1024
    private const val MAX_RESOURCE_PIXELS = 16L * 1024 * 1024
    private const val MAX_RESOURCES = 1024
    private const val MAX_FRAMES = 1000000
    private const val MAX_FRAME_OBJECTS = 100000

    fun parse(json: JSONObject, fields: VapConfigFields): VapConfigParseError? {
        return try {
            val info = json.getJSONObject("info")
            val version = info.getInt("v")
            if (VAP_CONFIG_VERSION != version) {
                return VapConfigParseError("current version=$VAP_CONFIG_VERSION target=$version")
            }
            fields.totalFrames = info.getInt("f")
            fields.width = info.getInt("w")
            fields.height = info.getInt("h")
            fields.videoWidth = info.getInt("videoW")
            fields.videoHeight = info.getInt("videoH")
            fields.orien = info.getInt("orien")
            fields.fps = info.getInt("fps")
            fields.isMix = info.getInt("isVapx") == 1
            val alpha = info.getJSONArray("aFrame") ?: return VapConfigParseError()
            fields.alpha = VideoRect(alpha.getInt(0), alpha.getInt(1), alpha.getInt(2), alpha.getInt(3))
            val rgb = info.getJSONArray("rgbFrame") ?: return VapConfigParseError()
            fields.rgb = VideoRect(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2), rgb.getInt(3))
            validate(json, fields)
        } catch (error: JSONException) {
            VapConfigParseError("json parse fail $error", error)
        }
    }

    private fun validate(json: JSONObject, fields: VapConfigFields): VapConfigParseError? {
        if (!validSize(fields.width, fields.height, MAX_VIDEO_PIXELS) ||
            !validSize(fields.videoWidth, fields.videoHeight, MAX_VIDEO_PIXELS) ||
            fields.totalFrames !in 1..MAX_FRAMES || fields.fps !in 1..240 ||
            !insideVideo(fields.alpha, fields) || !insideVideo(fields.rgb, fields)) {
            return VapConfigParseError("Invalid video dimensions, frame count, fps or packed rectangles")
        }
        if (!fields.isMix) return null
        val sources = json.getJSONArray("src")
        val frames = json.getJSONArray("frame")
        if (sources.length() > MAX_RESOURCES || frames.length() > fields.totalFrames) return VapConfigParseError()
        val ids = HashSet<String>()
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            if (!ids.add(source.getString("srcId")) ||
                !validSize(source.getInt("w"), source.getInt("h"), MAX_RESOURCE_PIXELS)) return VapConfigParseError()
        }
        var objectCount = 0L
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            if (frame.getInt("i") !in 0 until fields.totalFrames) return VapConfigParseError()
            objectCount += frame.getJSONArray("obj").length()
            if (objectCount > MAX_FRAME_OBJECTS) return VapConfigParseError()
        }
        return null
    }

    private fun validSize(w: Int, h: Int, maxPixels: Long): Boolean =
        w in 1..MAX_DIMENSION && h in 1..MAX_DIMENSION && w.toLong() * h <= maxPixels

    private fun insideVideo(rect: VideoRect?, fields: VapConfigFields): Boolean =
        rect != null && rect.x >= 0 && rect.y >= 0 && rect.w > 0 && rect.h > 0 &&
            rect.x.toLong() + rect.w <= fields.videoWidth && rect.y.toLong() + rect.h <= fields.videoHeight
}
