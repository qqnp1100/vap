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
package com.tencent.qgame.animplayer.mask

import com.tencent.qgame.animplayer.legacyRenderLogger
import com.tencent.qgame.vap.core.spi.MaskLayerShader

class MaskShader internal constructor(internal val coreShader: MaskLayerShader) {
    companion object {}

    constructor(edgeBlurBoolean: Boolean) : this(MaskLayerShader(edgeBlurBoolean, legacyRenderLogger))

    val uTextureMaskUnitLocation: Int get() = coreShader.uTextureMaskUnitLocation
    val aPositionLocation: Int get() = coreShader.aPositionLocation
    val aTextureMaskCoordinatesLocation: Int get() = coreShader.aTextureMaskCoordinatesLocation

    fun useProgram() = coreShader.useProgram()

    fun release() = coreShader.release()
}
