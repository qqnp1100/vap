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

import android.media.MediaExtractor
import android.media.MediaFormat
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.file.IFileContainer
import com.tencent.qgame.vap.core.spi.CoreMediaAccess

object MediaUtil {
    private const val TAG = "${Constant.TAG}.MediaUtil"
    const val MIME_HEVC = CoreMediaAccess.MIME_HEVC

    fun getExtractor(file: IFileContainer): MediaExtractor = CoreMediaAccess.openExtractor(file::setDataSource) {
        ALog.e(TAG, "extractor release after data source failure", it)
    }

    fun checkIsHevc(videoFormat: MediaFormat): Boolean = CoreMediaAccess.isHevc(videoFormat)

    fun selectVideoTrack(extractor: MediaExtractor): Int =
        CoreMediaAccess.selectTrack(extractor, "video/") { ALog.i(TAG, it) }

    fun selectAudioTrack(extractor: MediaExtractor): Int =
        CoreMediaAccess.selectTrack(extractor, "audio/") { ALog.i(TAG, it) }

    @Synchronized
    fun checkSupportCodec(mimeType: String): Boolean =
        CoreMediaAccess.checkSupportCodec(mimeType, { ALog.i(TAG, it) }, { ALog.e(TAG, "getSupportType $it") })
}
