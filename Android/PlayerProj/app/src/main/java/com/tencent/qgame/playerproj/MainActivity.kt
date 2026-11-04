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
package com.tencent.qgame.playerproj

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.tencent.qgame.playerproj.databinding.ActivityMainBinding
import com.tencent.qgame.playerproj.player.AnimActiveDemoActivity
import com.tencent.qgame.playerproj.player.AnimSimpleDemoActivity
import com.tencent.qgame.playerproj.player.AnimSpecialSizeDemoActivity
import com.tencent.qgame.playerproj.player.AnimVapxDemoActivity
import com.tencent.qgame.vap.compose.demo.DemoScreen
import com.tencent.qgame.vap.compose.demo.StressScreen

class MainActivity : ComponentActivity() {
    private var composeScenario: String? = null
    private var composeCycles = 8
    private val backFromCompose = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backFromCompose)
        val scenario = if (savedInstanceState == null) intent.getStringExtra("scenario")
            else savedInstanceState.getString("composeScenario")
        val defaultCycles = if (scenario == "stress") 200 else 8
        val cycles = savedInstanceState?.getInt("composeCycles", defaultCycles)
            ?: intent.getIntExtra("cycles", defaultCycles)
        if (savedInstanceState?.getBoolean("composeVisible") ?: (scenario != null)) showCompose(scenario, cycles)
        else showMenu()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("composeVisible", backFromCompose.isEnabled)
        outState.putString("composeScenario", composeScenario)
        outState.putInt("composeCycles", composeCycles)
        super.onSaveInstanceState(outState)
    }

    private fun showMenu() {
        backFromCompose.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btn1.setOnClickListener {
            startActivity(Intent(this, AnimSimpleDemoActivity::class.java))
        }
        binding.btn2.setOnClickListener {
            startActivity(Intent(this, AnimVapxDemoActivity::class.java))
        }
        binding.btn3.setOnClickListener {
            startActivity(Intent(this, AnimActiveDemoActivity::class.java))
        }
        binding.btn4.setOnClickListener {
            startActivity(Intent(this, AnimSpecialSizeDemoActivity::class.java))
        }
        binding.btnCompose.setOnClickListener { showCompose(null, 8) }
    }

    private fun showCompose(scenario: String?, cycles: Int) {
        composeScenario = scenario
        composeCycles = cycles
        backFromCompose.isEnabled = true
        if (scenario == "stress") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContent { StressScreen(cycles.coerceIn(1, 500)) }
        } else {
            setContent { DemoScreen(scenario, cycles.coerceIn(1, 40)) { moveTaskToBack(true) } }
        }
    }
}
