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

import android.content.res.AssetManager
import android.media.MediaExtractor
import com.tencent.qgame.vap.core.internal.source.AssetInput
import com.tencent.qgame.vap.core.internal.source.FileInput
import java.io.File

/** A legacy media input with separate extractor access and configuration cursor. */
interface VapMediaInput : VapConfigInput {
    fun setDataSource(extractor: MediaExtractor)
    fun close()
}

fun openFileInput(file: File): VapMediaInput = FileInput(file)

fun openAssetInput(assets: AssetManager, path: String): VapMediaInput = AssetInput(assets, path)
