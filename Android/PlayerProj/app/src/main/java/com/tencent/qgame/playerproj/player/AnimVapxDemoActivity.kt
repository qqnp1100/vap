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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.inter.IFetchResource
import com.tencent.qgame.animplayer.inter.OnResourceClickListener
import com.tencent.qgame.animplayer.mix.Resource
import com.tencent.qgame.animplayer.util.ALog
import com.tencent.qgame.animplayer.util.IALog
import com.tencent.qgame.animplayer.util.ScaleType
import com.tencent.qgame.playerproj.R
import com.tencent.qgame.playerproj.databinding.ActivityAnimSimpleDemoBinding
import java.util.*

/**
 * VAPX demo (融合特效Demo)
 * 必须使用组件里提供的工具才能生成VAPX动画
 */
class AnimVapxDemoActivity : DemoActivity(), IAnimListener {

    companion object {
        private const val TAG = "AnimVapxDemoActivity"
    }

    private var head1Img = true

    // 视频信息
    data class VideoInfo(val fileName: String, val md5: String)

    private val videoInfo = VideoInfo("vapx.mp4", "f981e0f094ead842ad5ae99f1ffaa1a1")

    // 动画View
    private lateinit var binding: ActivityAnimSimpleDemoBinding

    private var lastToast: Toast? = null
    private val startAfterLayout = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            if (right <= left || bottom <= top) return
            view.removeOnLayoutChangeListener(this)
            if (isDemoActive) playFile(videoInfo.fileName, videoInfo.md5, waitForStop = true)
        }
    }

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
        // 居中（根据父布局按比例居中并裁剪）
        animView.setScaleType(ScaleType.CENTER_CROP)
        /**
         * 注册资源获取类
         */
        animView.setFetchResource(object : IFetchResource {
            /**
             * 获取图片资源
             * 图片不可用时回调 result(null)，播放器会使用透明占位图。
             */
            override fun fetchImage(resource: Resource, result: (Bitmap?) -> Unit) {
                if (!isDemoActive) {
                    result(null)
                    return
                }
                /**
                 * srcTag是素材中的一个标记，在制作素材时定义
                 * 解析时由业务读取tag决定需要播放的内容是什么
                 * 比如：一个素材里需要显示多个头像，则需要定义多个不同的tag，表示不同位置，需要显示不同的头像，文字类似
                 */
                val srcTag = resource.tag
                if (srcTag.isNotEmpty()) {
                    val drawableId = if (head1Img) R.drawable.head1 else R.drawable.head2
                    head1Img = !head1Img
                    result(decodeAvatar(drawableId, resource.targetWidth, resource.targetHeight))
                } else {
                    result(null)
                }
            }

            /**
             * 获取文字资源
             */
            override fun fetchText(resource: Resource, result: (String?) -> Unit) {
                if (!isDemoActive) {
                    result(null)
                    return
                }
                val str = "恭喜 No.${1000 + Random().nextInt(8999)}用户 升神"
                val srcTag = resource.tag
                if (srcTag.isNotEmpty()) { // 此tag是已经写入到动画配置中的tag
                    result(str)
                } else {
                    result(null)
                }
            }

            /**
             * 播放完毕后的资源回收
             */
            override fun releaseResource(resources: List<Resource>) {
                resources.forEach {
                    it.bitmap?.recycle()
                }
            }
        })

        // 注册点击事件监听
        animView.setOnResourceClickListener(object : OnResourceClickListener {
            override fun onClick(resource: Resource) {
                lastToast?.cancel()
                lastToast = Toast.makeText(
                    this@AnimVapxDemoActivity,
                    "srcTag=${resource.tag} onClick ${resource.curPoint}",
                    Toast.LENGTH_LONG
                )
                lastToast?.show()
            }
        })

        // 注册动画监听
        animView.setAnimListener(this)
    }

    private fun decodeAvatar(drawableId: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inScaled = false }
        if (targetWidth <= 0 || targetHeight <= 0) {
            return BitmapFactory.decodeResource(resources, drawableId, options)
        }

        options.inJustDecodeBounds = true
        BitmapFactory.decodeResource(resources, drawableId, options)
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        // Retain 25% sampling margin and choose a power of two supported by BitmapFactory.
        val minWidth = (targetWidth * 5L + 3) / 4
        val minHeight = (targetHeight * 5L + 3) / 4
        var sampleSize = 1
        while (originalWidth / (sampleSize * 2L) >= minWidth &&
            originalHeight / (sampleSize * 2L) >= minHeight) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        return BitmapFactory.decodeResource(resources, drawableId, options)?.also { bitmap ->
            Log.i(TAG, "VAPX image original=${originalWidth}x${originalHeight} " +
                "target=${targetWidth}x${targetHeight} decoded=${bitmap.width}x${bitmap.height} " +
                "sample=$sampleSize bytes=${bitmap.byteCount}")
        }
    }

    override fun onDemoResume() {
        if (animView.width > 0 && animView.height > 0) {
            playFile(videoInfo.fileName, videoInfo.md5, waitForStop = true)
        } else {
            animView.addOnLayoutChangeListener(startAfterLayout)
        }
    }

    override fun onPause() {
        animView.removeOnLayoutChangeListener(startAfterLayout)
        super.onPause()
    }

    private fun play(videoInfo: VideoInfo) {
        playFile(videoInfo.fileName, videoInfo.md5)
    }

    /**
     * 视频信息准备好后的回调，用于检查视频准备好后是否继续播放
     * @return true 继续播放 false 停止播放
     */
    override fun onVideoConfigReady(config: AnimConfig): Boolean {
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
            animView.removeOnLayoutChangeListener(startAfterLayout)
            stopDemoPlayback()
        }
    }

}

