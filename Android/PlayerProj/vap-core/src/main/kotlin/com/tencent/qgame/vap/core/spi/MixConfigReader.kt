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

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class MixSourceDefinition(
    val id: String,
    val width: Int,
    val height: Int,
    val color: Int,
    val colorText: String,
    val tag: String,
    val sourceType: String,
    val loadType: String,
    val fitType: String,
    val style: String
)

data class MixFrameLayer(
    val sourceId: String,
    val z: Int,
    val position: VideoRect,
    val videoMask: VideoRect,
    val maskRotation: Int
)

data class MixFrameDefinition(val index: Int, val layers: List<MixFrameLayer>)

/** Reads existing VAPX fields. The enclosing config parser enforces the request budgets. */
object MixConfigReader {
    fun readSource(json: JSONObject): MixSourceDefinition {
        val id = json.getString("srcId")
        val width = json.getInt("w")
        val height = json.getInt("h")
        val colorText = json.optString("color", "#000000").ifEmpty { "#000000" }
        return MixSourceDefinition(
            id, width, height, Color.parseColor(colorText), colorText,
            json.getString("srcTag"), json.getString("srcType"), json.getString("loadType"),
            json.getString("fitType"), json.optString("style", "")
        )
    }

    fun forEachSource(json: JSONObject, accept: (MixSourceDefinition) -> Unit) {
        json.getJSONArray("src").forEachObject { accept(readSource(it)) }
    }

    fun readLayer(json: JSONObject): MixFrameLayer = MixFrameLayer(
        json.getString("srcId"), json.getInt("z"),
        json.getJSONArray("frame").toRect(), json.getJSONArray("mFrame").toRect(), json.getInt("mt")
    )

    fun readFrame(json: JSONObject): MixFrameDefinition {
        val index = json.getInt("i")
        val layers = ArrayList<MixFrameLayer>()
        json.getJSONArray("obj").forEachObject { layers.add(readLayer(it)) }
        layers.sortBy { it.z }
        return MixFrameDefinition(index, layers)
    }

    fun forEachFrame(json: JSONObject, accept: (MixFrameDefinition) -> Unit) {
        json.getJSONArray("frame").forEachObject { accept(readFrame(it)) }
    }

    private inline fun JSONArray.forEachObject(accept: (JSONObject) -> Unit) {
        for (i in 0 until length()) accept(getJSONObject(i))
    }

    private fun JSONArray.toRect() = VideoRect(getInt(0), getInt(1), getInt(2), getInt(3))
}
