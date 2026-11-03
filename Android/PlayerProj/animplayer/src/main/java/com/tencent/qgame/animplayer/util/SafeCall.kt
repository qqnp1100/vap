package com.tencent.qgame.animplayer.util

/** Keep application callbacks and independent release steps inside their own boundary. */
internal inline fun safeCall(name: String, action: () -> Unit) {
    try {
        action()
    } catch (error: Throwable) {
        ALog.e("AnimPlayer.Callback", name, error)
    }
}
