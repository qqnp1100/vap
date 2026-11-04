package com.tencent.qgame.vap.core.model

/** Reopenable local input for a VAP request. */
sealed interface VapSource {
    data class File(val file: java.io.File) : VapSource
    data class Asset(val path: String) : VapSource
    data class Uri(val uri: android.net.Uri) : VapSource
}
