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
package com.tencent.qgame.animplayer.util

import android.graphics.Bitmap
import com.tencent.qgame.animplayer.mix.Src
import com.tencent.qgame.vap.core.spi.MixBitmaps

object BitmapUtil {
    fun createEmptyBitmap(): Bitmap = MixBitmaps.createEmptyBitmap()

    fun createTxtBitmap(src: Src): Bitmap =
        MixBitmaps.createTextBitmap(src.w, src.h, src.txt, src.style == Src.Style.BOLD, src.color)
}
