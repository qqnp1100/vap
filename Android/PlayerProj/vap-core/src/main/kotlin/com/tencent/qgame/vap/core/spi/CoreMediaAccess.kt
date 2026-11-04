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

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.util.Locale

/** Shared extractor ownership and legacy codec discovery. Versioned with the host libraries. */
object CoreMediaAccess {
    const val MIME_HEVC = "video/hevc"

    private var codecsRead = false
    private val supportedTypes = HashSet<String>()

    fun openExtractor(
        setDataSource: (MediaExtractor) -> Unit,
        onReleaseFailure: (Throwable) -> Unit
    ): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor)
            return extractor
        } catch (error: Throwable) {
            try {
                extractor.release()
            } catch (releaseError: Throwable) {
                onReleaseFailure(releaseError)
            }
            throw error
        }
    }

    fun isHevc(format: MediaFormat): Boolean =
        format.getString(MediaFormat.KEY_MIME).orEmpty().contains("hevc")

    fun selectTrack(extractor: MediaExtractor, prefix: String, onSelected: (String) -> Unit): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) {
                onSelected("Extractor selected track $index ($mime): $format")
                return index
            }
        }
        return -1
    }

    @Synchronized
    fun checkSupportCodec(mime: String, onInfo: (String) -> Unit, onError: (Throwable) -> Unit): Boolean {
        if (!codecsRead) {
            codecsRead = true
            try {
                for (index in 0 until MediaCodecList.getCodecCount()) {
                    val info = MediaCodecList.getCodecInfoAt(index)
                    if (info.isEncoder) continue
                    for (type in info.supportedTypes) {
                        supportedTypes.add(type.lowercase(Locale.getDefault()))
                    }
                }
                onInfo("supportType=$supportedTypes")
            } catch (error: Throwable) {
                onError(error)
            }
        }
        return mime.lowercase(Locale.getDefault()) in supportedTypes
    }
}
