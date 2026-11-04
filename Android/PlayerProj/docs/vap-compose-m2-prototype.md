# VAP Compose M2 原型

`vap-compose` 提供 `VapAnimation`，库使用 BOM 2025.11.01、minSdk 23、compileSdk 36 和 JVM 17。M2 首轮验证使用当时的独立 Compose 应用，当前复跑使用统一的 `:app`。

`:app` 使用应用 ID `com.tencent.qgame.playerproj`，minSdk 为 23。安装后从原首页点击 **Compose Demo**，View 示例入口也保留在原首页。

原型支持 File 与 Assets 中的普通 VAP v2 素材，单次静音播放。Compose 通过 `AndroidEmbeddedExternalSurface(isOpaque = false)` 借出显示 Surface。core 复用视频解码器和 packed OES 渲染器，Compose 路径用 EGL14 创建 window surface 和 1×1 pbuffer。解绑先撤销 token，在渲染线程切到 pbuffer 并销毁 window surface，确认后才从宿主回调返回。Codec 与输入资源随后释放。

`playbackKey` 变化触发重播。重组只更新请求和事件回调，非零尺寸变化只更新渲染尺寸。宿主丢失 Surface 或离开 STARTED 后，未完成的请求在恢复时从头开始。M2 使用 Compose 测量和裁剪实现 Fit、Crop 与 Alignment，Surface 跟随测量后的内容区域，完整版本的宿主尺寸固定和 core 几何仍待实现。

音频、暂停、循环、VAPX、遮罩、点击与 Uri 属于后续接入范围。现有旧 View 路径使用 EGL10，Compose 原型共用解码与渲染实现，暂由 core 内的独立 Surface 会话协调器管理生命周期。API 16–20 真机验收按本轮安排延期，仍保留在 [M1 验收清单](vap-compose-m0-baseline.md)。

## 验证命令

在 `Android/PlayerProj` 执行。自动场景通过原首页 Activity 的 `scenario` 参数进入 Compose。

```sh
rtk proxy ./gradlew :vap-core:testDebugUnitTest :vap-core:lintDebug :vap-compose:lintDebug :vap-compose:assembleRelease :animplayer:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
rtk proxy adb install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario surface --ei cycles 40
rtk proxy adb logcat -d -s VAP-Compose-Demo:I VapComposeSurface:I
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario legacy
```

Surface 场景验证 100 次重组、两次尺寸变化、变换、40 次移除重建和显式重播。Legacy 场景应只产生一次失败事件。手动模式用棋盘背景和前景标签验证透明叠加，支持裁剪和旋转。销毁握手记录微秒耗时，按设备计算 P50、P95 和最大值。

## 独立 Demo 阶段的结果

- 离线联合构建通过。三模块 JVM 测试共 34 项通过，core 与 Compose Lint 均为 0 错误，Compose Release AAR、Compose Demo 和旧 View Demo 均构建成功。
- 运行依赖检查确认 Compose Demo 依赖 `vap-compose → vap-core`，旧 Demo 依赖 `animplayer → vap-core`。
- API 37 手机（25128PNA1C）使用新版 core 复跑旧 View 套件，46/46 通过，70.849 秒。旧 Demo APK SHA-256 为 `d5293d36f00e64ea4038ea8dca351e8b3b4f00d1e1097639f046ee5f85d1de2a`。[旧 View 原始日志](validation/device-m2-api37-view.log)

## API 37 Compose 真机验收

2026 年 11 月 3 日，设备 25128PNA1C，Android 17（API 37）。手机放行 USB 安装后重试成功。本地与手机安装的历史独立 APK SHA-256 均为 `06948c7d43464f431c101289f8bcd97cdb33a3cf55bcdf7954f3efe63802ee0d`。

| 用例 | 结果 |
| --- | --- |
| 透明叠加 | 播放中窗口截图可见棋盘穿过视频透明区域，动画及光效可见，Compose 前景标签正常显示。[截图](validation/device-m2-api37-compose-overlay.png) |
| 普通重组与尺寸变化 | 100 次重组、2 次 resize 和 1 次裁剪及旋转变化后，首帧成功交换事件仍为 1 次，无失败。 |
| Surface 重建与重播 | 40 次移除重建均收到新首帧，累计 41 次。显式重播后累计 42 次首帧、1 次完成、0 次失败。[Surface 日志](validation/device-m2-api37-compose-surface.log) |
| V1 拒绝 | `special_size_750.mp4` 产生 1 次失败、0 次首帧、0 次完成，错误为缺少 VAP v2 `vapc`。[V1 日志](validation/device-m2-api37-compose-legacy.log) |

40 次 `surface-destroyed` 的主线程同步解绑耗时，**P50 0.242 ms，P95 0.373 ms，最大 1.228 ms**，低于 100 ms 原型门槛。P50 取中位数，P95 取最近秩。显式重播产生的 `request` 解绑为 0.257 ms，单独记录。这组数据测量同步解绑，Codec 后台释放与 Foundation 回调其余开销另计。

API 37 的上述用例通过。M2 计划中的其他 API/GPU、受控的交换进行中销毁，以及直接统计 Codec 创建次数仍待验证。
