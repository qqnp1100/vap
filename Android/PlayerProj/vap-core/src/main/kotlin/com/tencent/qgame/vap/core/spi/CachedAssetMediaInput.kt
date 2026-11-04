package com.tencent.qgame.vap.core.spi

import android.content.res.AssetManager
import com.tencent.qgame.vap.core.internal.source.FileInput
import java.io.File
import java.io.IOException

private const val MAX_CACHED_ASSET_BYTES = 64L * 1024 * 1024

/** Opens an asset on a media worker, staging compressed assets in the caller's private cache. */
fun openCachedAssetInput(assets: AssetManager, path: String, cacheDir: File): VapMediaInput {
    try {
        return openAssetInput(assets, path)
    } catch (_: IOException) {
        // Compressed entries have an input stream but no independently seekable descriptor.
    }

    var temporary: File? = null
    try {
        val staged = assets.open(path, AssetManager.ACCESS_STREAMING).use { input ->
            val file = File.createTempFile("vap-asset-", ".mp4", cacheDir)
            temporary = file
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CACHED_ASSET_BYTES) {
                        throw IOException("Compressed asset exceeds the 64 MiB cache limit: $path")
                    }
                    output.write(buffer, 0, count)
                }
            }
            file
        }
        return TemporaryAssetInput(staged, FileInput(staged))
    } catch (error: Throwable) {
        temporary?.delete()
        throw error
    }
}

private class TemporaryAssetInput(
    private val temporary: File,
    private val input: VapMediaInput,
) : VapMediaInput by input {
    override fun close() {
        try {
            input.close()
        } finally {
            temporary.delete()
        }
    }
}
