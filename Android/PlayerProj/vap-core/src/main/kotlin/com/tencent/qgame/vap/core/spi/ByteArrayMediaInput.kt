package com.tencent.qgame.vap.core.spi

import android.annotation.TargetApi
import android.media.MediaDataSource
import com.tencent.qgame.vap.core.internal.source.ByteArrayDataSource
import com.tencent.qgame.vap.core.internal.source.ByteArrayInput

@TargetApi(23)
fun openByteArrayInput(bytes: ByteArray): VapMediaInput = ByteArrayInput(bytes)

@TargetApi(23)
fun openByteArrayDataSource(bytes: ByteArray): MediaDataSource = ByteArrayDataSource(bytes)
