# VAP Compose M3 接入记录

`VapAnimation` 提供 `VapPlaybackState.snapshot`、显式重播、暂停和恢复、有限或无限循环、音频启用与动态静音。状态通过 `StateFlow` 交付，播放事件带请求编号。同一 state 同时只能绑定一个宿主。Surface 或生命周期中断时，重新开始尚未完成的轮次。

Compose 复用 `vap-core` 的解码、VAPX 素材解析与渲染、图片和文字资源调度。资源以可取消的挂起接口接入，图片租约在释放或取消时关闭。点击按最近一次成功交换的图层快照命中。运行时遮罩支持设置、替换和移除，暂停时可重绘已锁存的帧。遮罩在最终目标上对 RGB 和 alpha 使用相同的可见性乘数。旧 View 继续使用原混合与回调路径。

数据源支持 File、Assets 和可 seek 的本地 `content://` Uri。URI 保留描述符 offset 和 length，管道来源报告 `SourceNotSeekableException`。压缩 Asset 可在应用缓存目录内暂存，单个输入上限 64 MiB。

## 复跑

在 `Android/PlayerProj` 执行。统一 Demo 的应用 ID 为 `com.tencent.qgame.playerproj`，minSdk 为 23。手动测试从原首页点击 **Compose Demo**，自动场景通过同一 Activity 的 `scenario` 参数启动。

```sh
rtk proxy ./gradlew :vap-core:testDebugUnitTest :vap-core:lintDebug :vap-compose:testDebugUnitTest :vap-compose:lintDebug :vap-compose:assembleRelease :animplayer:testDebugUnitTest :animplayer:assembleRelease :app:testDebugUnitTest :app:assembleDebug
rtk proxy adb install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario controls
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario vapx
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario uri
```

每个场景结束后再启动下一场景。用 `rtk proxy adb logcat -d -s VAP-Compose-Demo:I VapComposeSurface:I VapSurfaceAudio:I` 读取结果。

## 验证结果

以下真机记录来自 Demo 统一前的独立 Compose APK 和旧 View APK。历史哈希对应原测试产物。

最终离线联合构建通过。core、Compose、旧 View 与旧 Demo 共 36 项 JVM 测试通过，两库 Release AAR 和两套 Debug APK 均构建成功。core 与 Compose Lint 均为 0 错误。旧 View AAR 的 JVM 声明清单与 M1 AAR 一致。

2026 年 11 月 3 日在 25128PNA1C（API 37）上验证，Compose APK SHA-256 为 `b5114439a784b6bd70e8ee3ee084e0cef38e460fd5d75f17b9aa47f124065f20`。

| 场景 | 结果 |
| --- | --- |
| 播放控制 | 暂停、遮罩设置与移除请求、恢复均通过。有限循环恰好完成 2 轮，无限循环观察到 2 轮后主动移除，期间未额外完成。 |
| VAPX 与音频 | 加载 1 个图片与 1 段文字，图片租约关闭 1 次。窗口中可见融合图文，点击文字返回 `sourceId=1`、`tag=[sTxt1]`。音频线程写入 PCM，播放过程中切换静音，播放正常完成。[窗口截图](validation/device-m3-api37-vapx.png) |
| 本地 URI | 带 offset 的可 seek 描述符播放成功。非 seek 管道产生 `SourceNotSeekableException`。 |
| M2 回归 | 100 次重组、2 次尺寸变化、变换、40 次重建和显式重播通过。V1 只产生一次失败。 |

40 次 Surface 销毁同步解绑的 P50 为 0.267 ms，P95 为 0.466 ms，最大 1.100 ms。[Compose 原始日志](validation/device-m3-api37-compose.log)

同一台手机使用新版 core 冷启动旧 View Demo，**46/46 通过，耗时 70.905 秒**。APK SHA-256 为 `15be269da97d67d02430fd47745ea21bd5e6793680afbb5dda4275c6b3074464`。[旧 View 原始日志](validation/device-m3-api37-view.log)

## M3 后续验收

Surface 保持宿主尺寸。Compose 计算 Fit、Crop、Alignment 与布局方向对应的内容矩形，core 用同一变换绘制视频、VAPX 和遮罩，并用最后一次成功交换的逆变换处理点击。首次启动等待宿主提交几何，后台恢复时保留已完成轮次。公开失败事件携带错误类别、阶段和原始异常。

联合构建通过，40 项 JVM 测试通过。core 与 Compose Lint 无错误，旧 View AAR 的 JVM 声明清单与上一批一致。API 37 Compose APK SHA-256 为 `8a8d32f4f4b824a080257353aa2f271d23a9f82245be1e9fe381cfdfe3615ab5`。

| API 37 场景 | 结果 |
| --- | --- |
| 重组与重建 | 100 次重组、2 次 resize、裁剪与旋转变化、40 次重建和显式重播通过。40 次同步解绑 P50 0.305 ms、P95 0.426 ms、最大 1.854 ms。 |
| 前后台 | 完成第一轮后退到后台，保留同一请求编号和已完成的 1 轮。回到前台后从第二轮起点重播，最终累计 2 轮。 |
| 播放控制 | 首次因 Activity 离开组合而中断，重跑通过暂停恢复、两轮有限循环、无限循环观察两轮和主动移除。 |
| 暂停遮罩 | 同一暂停帧，遮罩设置后左侧远离边界的区域有 39,220 个像素改变，右侧远离边界的区域为 0。移除后测试区域与参考截图一致。统计 RGB 任一通道差值达到 12 的像素，左右区间分别为 x=60–559 和 x=640–1139，y=450–1269。[参考](validation/device-m3-api37-mask-reference.png)、[应用](validation/device-m3-api37-mask-applied.png)、[移除](validation/device-m3-api37-mask-removed.png) |
| 分类与取消 | 非法轮次报告 `InvalidRequest/Request`，缺少 `vapc` 报告 `InvalidVapConfig/Config`，不可 seek URI 报告 `SourceNotSeekable/Source`。挂起图片请求超时报告 `ResourceTimeout/Resource`，提供者收到一次取消。 |
| VAPX 点击 | 固定尺寸 Surface 上点击图文区域分别返回图片 `[sImg1]` 和文字 `[sTxt1]`。 |
| 音频行为 | VAPX 日志记录 PCM 写入及实际 AudioTrack 暂停、恢复和静音调用。短音轨、长音轨素材均按 3.2 秒视频的长度完成。 |

[API 37 原始日志](validation/device-m3-api37-followup.log)

旧 View Demo 冷启动后 **46/46 通过，耗时 71.055 秒**。APK SHA-256 为 `31f925c5ec8aa8152a626faad5af159a0dd57f3f139b1381bde001aba3c89150`。[旧 View 原始日志](validation/device-m3-api37-followup-view.log)

### 音画采样

临时开启 `log.tag.VapAvSync=DEBUG`，测试后恢复原值。每隔至少 100 ms 媒体 PTS，记录视频交给 Codec Surface 前的 PTS 和 AudioTrack 播放头映射的 PTS，只统计两者均有值的样本。绝对差值的 P95 使用最近秩。此指标测量调度边界，屏幕与扬声器的实际端到端延迟仍需另测。

| 素材 | 视频 / 音轨时长 | 样本数 | 绝对差 P95 / 最大 | Demo 从请求到完成 |
| --- | --- | ---: | ---: | ---: |
| `vapx.mp4`，含暂停、恢复、静音 | 12.000 / 11.964 s | 119 | 30 / 30 ms | — |
| 短音轨 | 3.200 / 1.021 s | 8 | 0 / 0 ms | 3.496 s |
| 长音轨 | 3.200 / 11.964 s | 26 | 0 / 0 ms | 3.440 s |

两份时长素材由旧 Demo 的 `demo.mp4` 视频和 `vapx.mp4` AAC 音轨无重编码合成，保留原 V2 `vapc`。短音轨对第二输入使用 `-t 1`，长音轨保留全部 AAC。FFmpeg remux 后，将 `demo.mp4` 的完整顶层 `vapc` box 追加到输出末尾。测试文件分别为 `short-audio.mp4` 和 `long-audio.mp4`。安装统一 Demo 后，将两份素材复制到 `com.tencent.qgame.playerproj` 私有 cache，使用 `audio-short` 和 `audio-long` 场景。

- 短音轨 SHA-256 `ca011ee6c24551b9c18fcb3da627de955c8e088e15f1854817cb96e6d3b8abe1`
- 长音轨 SHA-256 `1959e82f413835b7411ab22ace785bc96b454127a9ec187196fea49ec85f1558`

## 会话协调统一

View 的 `VapEngine` 与 Compose 的 `SurfacePlaybackController` 均使用 core 的 `PlaybackSessionCoordinator`。它统一分配会话编号、管理准备和启动状态、撤销会话，以及在音频退出后关闭输入源并交还工作槽。迟到或重复的空闲回调无法重复完成。View 保留忙时拒绝新播放、工作线程回调和原完成语义，Compose 保留最新请求优先及 Surface 同步解绑。

离线联合构建、三库 Release AAR 和两套 Debug APK 均通过。43 项 JVM 测试通过，其中新增 3 项覆盖忙时拒绝、等待音频退出期间的请求替换和迟到终结。core 与 Compose Lint 为 0 错误，旧 View AAR 的 JVM 声明清单与上一批一致。

### 旧 View 回调对照

在同一台 API 37 手机上，用 `0c3e5d2` 的拆分前源码加同一份调试测试文件构建对照 APK。对照 APK SHA-256 为 `ffab00cb7ec95fd616dd11eebac7037edf8a4ff842ed5e427777a35c4b83a51b`，统一协调后的 APK SHA-256 为 `7ea13c43392105fa48af289143e5acda8dbbfb8d576829897571a7629f9a5d89`。两版 `callback-contract` 各 3/3 通过，回调记录完全一致。

| 路径 | 回调顺序与线程 |
| --- | --- |
| 接受配置 | 渲染线程配置回调 → 解码线程开始和帧回调 → 渲染线程完成与销毁。帧回调收到同一个已调整配置。 |
| 拒绝配置 | 渲染线程配置回调 → 渲染线程完成。 |
| 配置失败 | 渲染线程失败 → 渲染线程完成。 |

三个完成回调中 `isRunning()` 均为 `false`。[拆分前后回调原始日志](validation/device-m3-api37-view-callback-contract.log)

统一协调后的旧 View 完整套件另行冷启动复跑，**46/46 通过，耗时 70.869 秒**。[旧 View 完整日志](validation/device-m3-api37-shared-view.log)

### Compose 功能复跑

同一台 API 37 手机使用 SHA-256 为 `4c58b1e24756d04b63eecf118402a191fbd28005c5db630ee574c4788dd1babc` 的 Compose Demo。11 个场景全部通过，覆盖 100 次重组与 40 次重建、前后台、播放控制、暂停遮罩、VAPX 图片和文字点击、URI、V1 拒绝、非法请求、资源超时和两种音轨时长。[Compose 功能日志](validation/device-m3-api37-shared-compose.log)

在 `Android/PlayerProj` 安装同一份 APK，分别运行 View 回调验证和 Compose 长测。

```sh
rtk proxy adb install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.player.StabilityTestActivity --es scenario callback-contract
rtk proxy adb shell run-as com.tencent.qgame.playerproj cat files/stability-results.log
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.MainActivity --es scenario stress --ei cycles 400
rtk proxy adb logcat -d -s VAP-Compose-Stress:I VapComposeSurface:I
```

### Compose 连续重挂

API 37 冷进程使用同一个 Compose APK 连续运行 **400/400 轮通过**，总耗时 131.751 秒，含最后 15 秒空闲采样。每轮播放静音 `demo.mp4`，首帧后移除并重建组件。首帧请求编号严格递增，累计 400 次首帧、0 次完成、0 次失败，移除后状态均回到 Idle。

| 采样点 | FD 总数 | `sync_file` | 线程总数 | 播放工作线程 | PSS（KiB） |
| --- | ---: | ---: | ---: | ---: | ---: |
| 冷启动基线 | 179 | 2 | 38 | 0 | 81,021 |
| 第 100 轮 | 292 | 71 | 51 | 1 | 136,900 |
| 第 200 轮 | 296 | 75 | 52 | 1 | 137,218 |
| 第 300 轮 | 307 | 86 | 53 | 1 | 140,646 |
| 第 400 轮 | 326 | 105 | 53 | 1 | 143,978 |
| 停止 1 秒 | 256 | 35 | 53 | 1 | 132,310 |
| 停止 5 秒 | 256 | 35 | 52 | 0 | 131,867 |
| 停止 15 秒 | 256 | 35 | 52 | 0 | 132,635 |

按每 100 轮分组，FD 中位数为 286.5、291、291、291，`sync_file` 中位数为 69、70、70、70。后 300 轮的 `sync_file` 峰值均为 105，出现多次回落。PSS 的分组中位数约为 136,026–136,870 KiB。停止后资源计数高于冷启动基线，本次运行中播放工作线程没有逐轮累积，并在 5 秒采样时归零。

400 次主线程同步解绑 **P50 0.564 ms、P95 0.766 ms、最大 3.763 ms**。时间覆盖显示输出解绑，媒体清理另行进行。[400 轮原始日志](validation/device-m3-api37-compose-stress-400.log)

## 范围与待验收

遮罩边缘模糊、压缩 Asset、异常资源回收的扩展场景及其他设备仍待验收。API 16–20 的旧 View 真机补测按安排延期。
