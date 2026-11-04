package com.tencent.qgame.vap.consumer.composeonly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tencent.qgame.vap.compose.VapAnimation
import com.tencent.qgame.vap.compose.VapEvent
import com.tencent.qgame.vap.consumer.FixtureResult
import com.tencent.qgame.vap.core.model.VapSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = FixtureResult(filesDir, TAG)
        setContent {
            VapAnimation(VapSource.Asset("demo.mp4"), audioEnabled = false) { event ->
                when (event) {
                    is VapEvent.Started -> result.note("STARTED")
                    is VapEvent.Completed -> result.note("COMPLETED")
                    is VapEvent.Failed -> result.fail("FAILED kind=${event.error.kind}", event.cause)
                    is VapEvent.ResourceClick -> Unit
                }
            }
        }
    }

    private companion object { const val TAG = "VapMavenCompose" }
}
