package com.tencent.qgame.vap.core.spi

import com.tencent.qgame.vap.core.internal.config.VapcBoxReader

/** Random-read port for the VAP configuration parser, versioned with the two host libraries. */
interface VapConfigInput {
    fun startRandomRead()

    /** Total source bytes, or -1 when unknown. */
    fun getLength(): Long

    /** Returns bytes read, or -1 at EOF. */
    fun read(bytes: ByteArray, offset: Int, length: Int): Int

    /** Advances by exactly [length] bytes, or throws if the advance is incomplete. */
    fun skipFully(length: Long)

    fun closeRandomRead()
}

const val MAX_VAP_CONFIG_BYTES = VapcBoxReader.MAX_CONFIG_BYTES

/** Returns the top-level vapc payload, or null when the input has no vapc box. */
fun readVapConfig(input: VapConfigInput): ByteArray? = VapcBoxReader.read(input)
