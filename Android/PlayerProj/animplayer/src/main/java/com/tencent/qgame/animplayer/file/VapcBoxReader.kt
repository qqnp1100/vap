package com.tencent.qgame.animplayer.file

import com.tencent.qgame.vap.core.spi.MAX_VAP_CONFIG_BYTES
import com.tencent.qgame.vap.core.spi.VapConfigInput
import com.tencent.qgame.vap.core.spi.readVapConfig

/** Adapts legacy file containers to the shared config parser. */
internal object VapcBoxReader {
    const val MAX_CONFIG_BYTES = MAX_VAP_CONFIG_BYTES

    fun read(container: IFileContainer): ByteArray? = readVapConfig(object : VapConfigInput {
        override fun startRandomRead() = container.startRandomRead()
        override fun getLength() = container.getLength()
        override fun read(bytes: ByteArray, offset: Int, length: Int) = container.read(bytes, offset, length)
        override fun skipFully(length: Long) = container.skipFully(length)
        override fun closeRandomRead() = container.closeRandomRead()
    })
}
