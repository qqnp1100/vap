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
package com.tencent.qgame.animplayer.mix

import android.graphics.Bitmap
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.vap.core.spi.MixConfigReader
import com.tencent.qgame.vap.core.spi.MixSourceDefinition
import org.json.JSONObject

class Src private constructor(definition: MixSourceDefinition) {
    constructor(json: JSONObject) : this(MixConfigReader.readSource(json))

    companion object {
        private const val TAG = "${Constant.TAG}.Src"

        @JvmSynthetic
        internal fun fromDefinition(definition: MixSourceDefinition) = Src(definition)
    }

    enum class SrcType(val type: String) {
        UNKNOWN("unknown"),
        IMG("img"),
        TXT("txt"),
    }

    enum class LoadType(val type: String) {
        UNKNOWN("unknown"),
        NET("net"), // 网络加载的图片
        LOCAL("local"), // 本地加载的图片
    }

    enum class FitType(val type: String) {
        FIT_XY("fitXY"), // 按原始大小填充纹理
        CENTER_FULL("centerFull"), // 以纹理中心点放置
    }

    enum class Style(val style: String) {
        DEFAULT("default"),
        BOLD("b"), // 文字粗体
    }

    var srcId = ""
    var w = 0
    var h = 0
    var drawWidth = 0
    var drawHeight = 0
    var srcType = SrcType.UNKNOWN
    var loadType = LoadType.UNKNOWN
    var srcTag = ""
    var txt = ""
    var style = Style.DEFAULT
    var color: Int = 0
    var fitType = FitType.FIT_XY
    var srcTextureId = 0
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            genDrawSize(value)
        }

    init {
        srcId = definition.id
        w = definition.width
        h = definition.height
        color = definition.color
        srcTag = definition.tag
        txt = srcTag

        srcType = when (definition.sourceType) {
            SrcType.IMG.type -> SrcType.IMG
            SrcType.TXT.type -> SrcType.TXT
            else -> SrcType.UNKNOWN
        }
        loadType = when (definition.loadType) {
            LoadType.NET.type -> LoadType.NET
            LoadType.LOCAL.type -> LoadType.LOCAL
            else -> LoadType.UNKNOWN
        }
        fitType = when (definition.fitType) {
            FitType.CENTER_FULL.type -> FitType.CENTER_FULL
            else -> FitType.FIT_XY
        }
        style = when (definition.style) {
            Style.BOLD.style -> Style.BOLD
            else -> Style.DEFAULT
        }
        ALog.i(TAG, "${toString()} color=${definition.colorText}")
    }

    private fun genDrawSize(bitmap: Bitmap?) {
        val bw = bitmap?.width?: w
        val bh = bitmap?.height?: h
        drawWidth = bw
        drawHeight = bh
        if (fitType == FitType.CENTER_FULL) {
            if (w == 0 || h == 0) {
                return
            }
            // 按src w h进行centerCrop处理
            val srcRate = w.toFloat() / h.toFloat()
            val bitmapRate = bw.toFloat() / bh.toFloat()

            if (bitmapRate >= srcRate) {
                drawHeight = h
                drawWidth = (h * bitmapRate).toInt()
            } else {
                drawWidth = w
                drawHeight = (w / bitmapRate).toInt()
            }
        }
    }


    override fun toString(): String {
        return "Src(srcId='$srcId', srcType=$srcType, loadType=$loadType, srcTag='$srcTag', bitmap=$bitmap, txt='$txt')"
    }

}


class SrcMap(json: JSONObject) {
    val map = HashMap<String, Src>()

    init {
        MixConfigReader.forEachSource(json) { definition ->
            val src = Src.fromDefinition(definition)
            if (src.srcType != Src.SrcType.UNKNOWN) { // 不认识的srcType丢弃
                map[src.srcId] = src
            }
        }
    }
}