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

import kotlin.math.ceil

object MixGeometry {
    fun targetSizes(
        layerBounds: Sequence<Pair<String, VideoRect>>,
        sourceIds: Set<String>,
        logicalWidth: Int,
        logicalHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Map<String, Pair<Int, Int>> {
        val bounds = HashMap<String, Pair<Int, Int>>()
        layerBounds.forEach { (sourceId, rect) ->
            if (sourceId in sourceIds) {
                val old = bounds[sourceId]
                bounds[sourceId] = maxOf(old?.first ?: 0, rect.w) to maxOf(old?.second ?: 0, rect.h)
            }
        }
        val scaleX = displayWidth.toDouble() / logicalWidth
        val scaleY = displayHeight.toDouble() / logicalHeight
        return bounds.mapValues { (_, size) ->
            ceil(size.first * scaleX).toInt() to ceil(size.second * scaleY).toInt()
        }
    }

    /** [layers] are visited in the adapter's hit order. Coordinates are in the displayed video. */
    fun <T> hitLayer(
        x: Float,
        y: Float,
        displayWidth: Int,
        displayHeight: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        layers: Iterable<T>,
        bounds: (T) -> VideoRect?
    ): T? {
        if (displayWidth == 0 || displayHeight == 0) return null
        val logicalX = (x * logicalWidth / displayWidth.toFloat()).toInt()
        val logicalY = (y * logicalHeight / displayHeight.toFloat()).toInt()
        return layers.firstOrNull { layer ->
            val rect = bounds(layer) ?: return@firstOrNull false
            logicalX >= rect.x && logicalX <= rect.x + rect.w &&
                logicalY >= rect.y && logicalY <= rect.y + rect.h
        }
    }
}
