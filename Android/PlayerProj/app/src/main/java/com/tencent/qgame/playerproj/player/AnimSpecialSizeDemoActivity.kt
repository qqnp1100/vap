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
package com.tencent.qgame.playerproj.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.Constant
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IALog
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.playerproj.databinding.ActivityAnimSimpleDemoBinding

/**
 * 播放宽高不是16的倍数的特殊尺寸的动画demo，这里以special_size_750.mp4为例，size = 750 x 814
 */
class AnimSpecialSizeDemoActivity : DemoActivity(), IAnimListener {

    companion object {
        private const val TAG = "AnimSpecialSizeActivity"
    }

    // 视频信息
    data class VideoInfo(val fileName: String,val md5:String)

    // 修改素材时同步更新校验值；缓存损坏或版本变化时会重新复制。
    private val videoInfo = VideoInfo("special_size_750.mp4", "2acde1639ad74b8bd843083246902e23")

    // 动画View
    private lateinit var binding: ActivityAnimSimpleDemoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnimSimpleDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        animView = binding.playerView
        init()
    }

    private fun init() {
        // 初始化日志
        initLog()
        // 初始化调试开关
        initTestView()
        // 视频左右对齐（rgb左\alpha右）
        animView.setVideoMode(Constant.VIDEO_MODE_SPLIT_HORIZONTAL_REVERSE)
        // 兼容老版本视频资源
        animView.enableVersion1(true)
        // 居中（根据父布局按比例居中并全部显示，默认fitXY）
        animView.setScaleType(ScaleType.FIT_CENTER)
        // 注册动画监听
        animView.setAnimListener(this)
    }

    override fun onDemoResume() {
        playFile(videoInfo.fileName, videoInfo.md5, waitForStop = true)
    }

    private fun play(videoInfo: VideoInfo) {
        playFile(videoInfo.fileName, videoInfo.md5)
    }

    /**
     * 视频信息准备好后的回调，用于检查视频准备好后是否继续播放
     * @return true 继续播放 false 停止播放
     */
    override fun onVideoConfigReady(config: AnimConfig): Boolean {

        if (!isDemoActive) return false
        uiHandler.post {
            if (!isDemoActive) return@post
            val w = dp2px(this,400f).toInt()
            val lp = animView.layoutParams
            lp.width = w
            lp.height = (w * config.height *1f / config.width).toInt()
            animView.layoutParams = lp
        }
        return isDemoActive
    }

    /**
     * 视频开始回调
     */
    override fun onVideoStart() {
        Log.i(TAG, "onVideoStart")
    }

    /**
     * 视频渲染每一帧时的回调
     * @param frameIndex 帧索引
     */
    override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {
    }

    /**
     * 视频播放结束(失败也会回调onComplete)
     */
    override fun onVideoComplete() {
        Log.i(TAG, "onVideoComplete")
        onDemoPlaybackComplete()
    }

    /**
     * 播放器被销毁情况下会调用onVideoDestroy
     */
    override fun onVideoDestroy() {
        Log.i(TAG, "onVideoDestroy")
    }

    /**
     * 失败回调
     * 一次播放时可能会调用多次，建议onFailed只做错误上报
     * @param errorType 错误类型
     * @param errorMsg 错误消息
     */
    override fun onFailed(errorType: Int, errorMsg: String?) {
        Log.i(TAG, "onFailed errorType=$errorType errorMsg=$errorMsg")
    }

    private fun initLog() {
        ALog.isDebug = false
        ALog.log = object : IALog {
            override fun i(tag: String, msg: String) {
                Log.i(tag, msg)
            }

            override fun d(tag: String, msg: String) {
                Log.d(tag, msg)
            }

            override fun e(tag: String, msg: String) {
                Log.e(tag, msg)
            }

            override fun e(tag: String, msg: String, tr: Throwable) {
                Log.e(tag, msg, tr)
            }
        }
    }

    private fun initTestView() {
        binding.btnLayout.visibility = View.VISIBLE
        /**
         * 开始播放按钮
         */
        binding.btnPlay.setOnClickListener {
            play(videoInfo)
        }
        /**
         * 结束视频按钮
         */
        binding.btnStop.setOnClickListener {
            stopDemoPlayback()
        }
    }

    private fun dp2px(context: Context, dp: Float): Float {
        val scale = context.resources.displayMetrics.density
        return dp * scale + 0.5f
    }
}

