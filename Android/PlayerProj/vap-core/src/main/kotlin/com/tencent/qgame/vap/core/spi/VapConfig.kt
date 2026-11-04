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

import com.tencent.qgame.vap.core.internal.config.VapConfigParser
import org.json.JSONObject

const val VAP_CONFIG_VERSION = 2

/** Mutable parsing state. A failed parse can leave previously read fields populated for legacy hosts. */
class VapConfigFields(
    var totalFrames: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
    var videoWidth: Int = 0,
    var videoHeight: Int = 0,
    var orien: Int = 0,
    var fps: Int = 0,
    var isMix: Boolean = false,
    var alpha: VideoRect? = null,
    var rgb: VideoRect? = null
)

class VapConfigParseError internal constructor(val message: String? = null, val cause: Throwable? = null)

fun decodeVapConfig(bytes: ByteArray): JSONObject = JSONObject(String(bytes, Charsets.UTF_8))

/** Populates [fields] and returns a failure when the VAP v2 metadata is invalid. */
fun parseVapConfig(json: JSONObject, fields: VapConfigFields): VapConfigParseError? =
    VapConfigParser.parse(json, fields)

/** Returns the legacy layout while preserving truncation of odd packed dimensions. */
fun splitLegacyVideo(
    videoWidth: Int,
    videoHeight: Int,
    vertical: Boolean,
    rgbFirst: Boolean
): VideoRenderConfig {
    val width = if (vertical) videoWidth else videoWidth / 2
    val height = if (vertical) videoHeight / 2 else videoHeight
    val first = VideoRect(0, 0, width, height)
    val second = if (vertical) VideoRect(0, height, width, height) else VideoRect(width, 0, width, height)
    val rgb = if (rgbFirst) first else second
    val alpha = if (rgbFirst) second else first
    return VideoRenderConfig(width, height, videoWidth, videoHeight, rgb, alpha)
}
