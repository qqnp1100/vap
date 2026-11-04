package com.tencent.qgame.vap.compose.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/** Supplies bounded asset and pipe descriptors for the demo's Uri playback checks. */
class SampleContentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Samples are read-only")
        return when (uri.path) {
            "/sample" -> checkNotNull(context).assets.openFd("demo.mp4")
            "/denied" -> throw SecurityException("Sample URI access denied")
            "/pipe" -> {
                val (read, write) = ParcelFileDescriptor.createPipe()
                try {
                    write.close()
                } catch (error: Throwable) {
                    read.close()
                    throw error
                }
                AssetFileDescriptor(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            else -> throw FileNotFoundException("Unknown sample: ${uri.path}")
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.path == "/sample" || uri.path == "/pipe") "video/mp4" else null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
