package com.tencent.qgame.animplayer.file

import android.media.MediaExtractor
import com.tencent.qgame.vap.core.spi.VapMediaInput

/** Bridges the built-in media inputs without changing the legacy file-container interface. */
internal class LegacyFileInput(private val input: VapMediaInput) : IFileContainer {
    override fun setDataSource(extractor: MediaExtractor) = input.setDataSource(extractor)
    override fun startRandomRead() = input.startRandomRead()
    override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
    override fun skip(pos: Long) = input.skipFully(pos)
    override fun getLength() = input.getLength()
    override fun skipFully(length: Long) = input.skipFully(length)
    override fun closeRandomRead() = input.closeRandomRead()
    override fun close() = input.close()
}
