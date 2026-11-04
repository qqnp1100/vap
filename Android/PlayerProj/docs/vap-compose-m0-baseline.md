# VAP Compose M0 验证基线

2026 年 11 月 3 日。源码基线为 `0c3e5d2`（`vap:2.0.30`），与真机记录对应的 `4d06df7` 仅有发布配置和 README 差异。旧 View 的 minSdk 为 16、JVM 目标为 1.8，仍沿用既有编译配置。本文记录拆分前证据，供拆分后在同样条件下对照。

## View 兼容行为

| 项目 | 拆分前契约 |
| --- | --- |
| 入口 | `AnimView` 的 XML 类名、公开 Java/Kotlin 方法，以及 File、Assets 和自定义 `IFileContainer` 入口保留。自定义容器继续接收原有读取、设置数据源、关闭调用。 |
| 配置与回调 | V2 保留旧 `onVideoConfigReady` 的布尔拒绝、可变 `AnimConfig` 和原工作线程回调。旧 `onVideoRender` 计数反映解码回调，不能替换为交换帧数或屏幕 FPS。配置拒绝的已有用例收到一次完成，无开始和错误。运行时 EGL 失败的已有用例收到一次失败、一次完成和一次销毁。 |
| 默认行为 | 缩放默认 `FIT_XY`，保留自定义 `IScaleType`。旧静音在启动时跳过音频输出。无 `vapc` 的素材继续按旧格式开关选择兼容路径。 |
| 资源与退出 | 保留 VAPX 图片、文字、点击、独立遮罩与自定义提供者。停止、detach 和错误后的释放应可重播，迟到的资源由原提供者归还。 |

## 已有运行证据

| 环境 | 结果与证据 |
| --- | --- |
| JVM 与构建 | 第三批稳定性验证中，旧 Demo 调试 APK 构建成功，两模块 JVM 测试共 28 项通过。覆盖 `vapc`/容器重开、YUV 帧槽与平面复制、帧映射与旧 API 单帧规则、遮罩配置和 Demo 文件缓存。[实施记录](performance-memory-stability-implementation.md) |
| API 33，vivo V2046A | 旧版完整套件 46/46，耗时 75.752 秒，含 20 轮停止与重挂、配置拒绝与重播、资源超时与迟到、有声 VAPX、EGL 失败和重播。普通纹理的故意慢渲染用例两轮匹配 474 个锁存帧、跳过 6 帧，两轮末帧索引均为 239。旧版特殊尺寸 YUV 素材自然完成时 119 次绘制，使用 1 个帧槽、池满丢帧 0。[原始日志](validation/device-stage3.log) |
| API 33，视觉抽查 | `demo.mp4` 的透明叠加和朝向、`vapx.mp4` 的头像和文字、独立 Bitmap 遮罩裁切及 `special_size_750.mp4` 的心形画面可见。证据是窗口截图抽查的文字记录。边缘像素、旋转矩阵和裁切的定帧金图仍待采集。[实施记录](performance-memory-stability-implementation.md) |
| API 37，25128PNA1C | ADB 识别到真机。设备上原先没有 Demo，尝试安装与 API 33 记录哈希一致的 APK 时，设备返回 `INSTALL_FAILED_USER_RESTRICTED`。当前只有设备识别结果。 |
| API 16、API 17–20 | 现有 JVM 用例覆盖旧版单帧提交逻辑和 YUV 平面向量。API 16 的 EGL10、旧版设备的 Codec 输出缓冲以及实际锁存行为需在对应设备上补测。 |

记录所用调试 APK 的 SHA-256 为 `43b516496891f90557cdf6eb22b759d1d3fac604e5383fedcebd797508c75289`。

## 拆分后的复跑口径

当前统一 Demo 保留旧 View 调试入口，应用 ID 为 `com.tencent.qgame.playerproj`，minSdk 为 23。历史日志和哈希仍对应各次验收当时的 APK。

```sh
rtk proxy ./gradlew :animplayer:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
rtk proxy adb install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.player.StabilityTestActivity --ei cycles 20 --es scenario all --ez autorun true --ez audio true
rtk proxy adb shell run-as com.tencent.qgame.playerproj cat files/stability-results.log
```

上面的命令在 `Android/PlayerProj` 执行。复跑记录设备型号、API、APK 哈希与完整日志。除现有调试套件外，M1 还需覆盖 XML 及 Java/Kotlin 消费、允许的配置调整、工作线程回调、业务自定义容器、静音启动、缩放及旧 API 真机路径。视觉对照在相同设备、素材、尺寸和背景下捕获同一素材帧。API 37 需先在设备端允许安装该调试 APK。

## M1 首批拆分验证

`vap-core` 的 JVM 测试、Debug Lint 与 Release AAR 构建均通过，`animplayer` 的 JVM 测试与 Release AAR 构建，以及旧 `app` 的 JVM 测试和 Debug APK 构建也通过。与拆分前的 Release AAR 对照 JVM 清单，仅增加编译器生成的 `VapcBoxReader$read$1` 适配类。M1 的真机与视觉验收仍待复跑。

## M1 后续拆分

| 批次 | 迁入 `vap-core` | 旧 View 兼容点 |
| --- | --- | --- |
| 配置与数据源 | v2 配置读取和校验、v1 分屏计算、File/Assets/字节数组输入 | 保留可变配置、默认 FPS、旧格式开关和自定义 `IFileContainer` 契约。Assets 描述符使用 API 16 可调用的 `close()`。 |
| 视频解码与渲染 | Codec 循环、HEVC 检查、帧匹配、YUV 缓冲、EGL10、OES/YUV 渲染及着色器 | 保留解码线程回调和旧 YUV 入口。View 层持有显示 Surface，core 借用 Surface 并先释放 EGL。 |
| VAPX 与独立遮罩 | 素材字段解析、资源请求的取消与超时、迟到资源归还、文字位图、图层和遮罩 GLES | 保留原资源接口、可变素材对象、点击消费顺序、遮罩与融合效果。MotionEvent 和 View 资源适配仍在 `animplayer`。 |
| 音频 | Codec/AudioTrack、PCM 输出、循环和释放 | 静音仍跳过音频启动。旧 `AudioPlayer` 可变字段继续映射到实际播放状态。 |
| 播放会话 | 会话编号、准备取消、延迟启动、循环和音频退出后的终结 | 保留配置回调、Surface 等待、源关闭、完成与销毁通知的原有顺序。 |

工作树的联合检查使用以下命令。

```sh
rtk proxy ./gradlew --offline :vap-core:testDebugUnitTest :vap-core:lintDebug :vap-core:assembleRelease :animplayer:testDebugUnitTest :animplayer:assembleRelease :app:testDebugUnitTest :app:assembleDebug
```

各批次在隔离源码副本上分别通过旧 View JVM 测试、Demo Debug APK 和 Release AAR 构建。配置与数据源批次 30 项，视频与渲染批次 30 项，VAPX 与遮罩批次 28 项，音频批次 28 项，音频加会话批次 30 项。最终联合检查 34 项 JVM 测试通过，core Lint 为 0 错误、6 条提示，涉及平台 API 注解与 Codec 返回值分支。

JVM 声明对照使用 [拆分前清单](validation/animplayer-2.0.30-jvm-api.txt)和 [AAR 提取脚本](../gradle/dump-aar-api.py)。比较拆分后两个 AAR 的声明并集，忽略成员顺序。旧公开和受保护签名保留，`YuvFrame`、`YuvFramePool`、`YuvPlaneCopy` 以原 JVM 名称随 core 交付。差异包括新增适配器，以及消失的三个旧内部或生成类型 `AudioPlayer$IdleAction`、`HardDecoder$release$1$10` 和 `FrameSet$special$$inlined$sortBy$1`。此检查覆盖 JVM 声明，Kotlin 元数据和运行行为单独验证。拆分后的 Maven 依赖发布及消费验证归入 M4。

拆分批次初验时，API 37 设备拒绝了 USB 安装，返回 `INSTALL_FAILED_USER_RESTRICTED`。

## M1 API 37 真机复跑

2026 年 11 月 3 日，设备 `25128PNA1C`，Android 17（API 37）。运行上面的完整套件，包含 20 轮停止重挂和有声音轨用例。本地与手机已安装 APK 的 SHA-256 均为 `fdf1d02888e62552027b789ddc28920efba6800b15daeeb6e38cce6acbdd696d`。

首次安装使用了拆分前的调试故障注入字段路径，`egl-render-failure` 超时。修正后从冷启动复跑，将注入点切换到 core 的 EGL 窗口对象。调试套件也将严格的 Surface 时间戳递增检查限定在 API 21 及以上，较早 API 仍按锁存索引校验 VAPX 融合帧。

**46/46 通过，耗时 70.624 秒。** VAPX 两轮匹配 474 帧、跳过 6 帧，末帧索引 239，与拆分前 API 33 的用例计数一致。EGL 注入错误后各收到一次失败、完成、销毁和资源归还，同一 View 重播通过。YUV 自然完成绘制 119 帧，停止重挂用例绘制 12 帧，两项均使用 1 个帧槽且池满丢帧为 0。有声 VAPX 启动 PCM 一次，音频错误为 0。[原始日志](validation/device-m1-api37.log)

功能套件外，FD 从启动后的 209 增至首轮重挂后的 355，结束后为 481。空闲快照仍有 480 个 FD，其中 276 个为 `anon_inode:sync_file`。首轮重挂后和结束后均为 44 个线程。API 33 历史数据来自另一设备，这一观察触发了下面的同机拆分前对照。

## API 37 同机重挂长跑

对照组用 `git archive 0c3e5d2ccda578c0a2e57c3838f03f28c641dcf4` 构建，只替换调试套件文件以共享逐轮采样。两组 `demo.mp4` SHA-256 相同，均为 `78b260236404b79c6b87f16a2747503f6219ca7ef8724187c6ae3627907d1ae5`。安装后核对设备上 APK 的 SHA-256 与本地一致。

| 组别 | APK SHA-256 |
| --- | --- |
| 拆分前 | `10f1a86a174ae802d61b50078a5315265a47259930e87da46e6e551cc10d46d9` |
| M1 | `3d30964db5f5eb435bc9c3f2670f5b3bba61ef8ea101520f9143b190a0e94c8f` |

同一台 API 37 手机按 M1、拆分前、拆分前、M1 顺序运行。每次从冷进程开始，执行 200 轮静音 `demo.mp4` 停止、移除 View 和重挂。每轮结束后读取进程的 FD 链接，单独计数 `anon_inode:sync_file`。套件结束时移除 View、调用 GC 并等候约 1 秒，随后用 ADB 另取即时和约 15 秒后的进程空闲快照。

```sh
rtk proxy adb shell am start -S -W -n com.tencent.qgame.playerproj/.player.StabilityTestActivity --ei cycles 200 --es scenario detach-attach --ez autorun true --ez audio false --ez syncFd true
```

| 运行顺序 | 通过 | `sync_file` 峰值 / 逐轮中位数 | 大幅回落至 35 的轮次 | 第 200 轮 | 空闲即时 / 15 秒 |
| --- | ---: | ---: | --- | ---: | ---: |
| M1 第一次 | 200/200 | 407 / 182 | 30、93、151 | 329 | 329 / 329 |
| 拆分前第一次 | 200/200 | 407 / 182 | 28、91、150 | 335 | 29 / 29 |
| 拆分前第二次 | 200/200 | 407 / 182 | 28、91、150 | 335 | 335 / 335 |
| M1 第二次 | 200/200 | 407 / 182 | 28、91、149 | 341 | 341 / 341 |

四次共 800 轮全部通过，每轮均有 3 次解码回调。每次运行的 199 次相邻轮次变化中，有 195 或 196 次增加 6 个 `sync_file`，其余包括三次大幅回落。后 100 轮的峰值为拆分前 382、383，M1 两次均为 377。逐轮采样各有一个 FD 链接未读到，未归入 `sync_file`，外部空闲快照通过进程 FD 目录另行计数。[逐轮采样与空闲快照](validation/device-m1-api37-sync-fd.log)

**结论：本机 200 轮重挂未发现 M1 引入持续增长回归。** 拆分前已存在相同的分段增长和回落，两版峰值、中位数及回落轮次接近。停播后仍可能保留约 330 个 `sync_file`，拆分前两次空闲采样也有明显差异。这组结果无法确定原生 FD 的持有者或更长空闲时间内的关闭时点。

## API 16–20 待补测

至少覆盖一台 API 16 和一台 API 17–20 真机。优先选 API 19 或 20 作为第二台，以覆盖 API 18 起使用的 `quitSafely()`，并记录设备、Codec 名称、素材、APK SHA-256 与原始日志。独立 Maven `view-only` 消费工程可在旧设备验证基础播放。46 项稳定性套件需要保留了该入口的历史低版本 APK，或为旧设备准备同等用例的 View 验收应用。[M4 消费工程](vap-compose-m4-publication.md)

历史低版本 Debug APK 的 manifest 声明 `minSdk=16`，`apksigner verify --min-sdk-version 16` 已验证其 v1/JAR 签名，core 单元测试、core 与 Demo Debug Lint 和 APK 构建均通过。若设备拒绝 `run-as`，改用 `rtk proxy adb logcat -d -s VAP-Stability` 导出稳定性结果。API 16–20 播放路径仍待真机验证。

| 路径 | 用例和通过条件 |
| --- | --- |
| EGL10 与 Surface 生命周期 | API 16 实播 OES 和 YUV，核对透明、方向、尺寸变化与重挂。故障注入后应各收到一次错误、完成和销毁通知，VAPX 资源归还一次，同一 View 可重播。停止后画面与音频均退出，20 轮重挂无持续积累的播放线程和文件句柄。 |
| 旧 H.264 Codec 缓冲与锁存 | 验证旧 buffer 数组及布尔 `releaseOutputBuffer` 路径。普通素材两轮有一次开始、完成和销毁，至少 160 次解码回调、两次第 0 帧。VAPX 故意延迟渲染后按帧索引检查融合层，两轮末帧均为 239，无锁存超时。API 16–20 以单帧在途和索引匹配验收。 |
| v1 特殊尺寸 YUV | 播放 `special_size_750.mp4`，核对 750×814 心形画面、色彩和裁剪。自然完成及停止重挂均通过，帧槽最多 2 个。记录实际输出格式、stride/crop，并在设备发出 `INFO_OUTPUT_BUFFERS_CHANGED` 时核对缓冲刷新。 |
| 配置、输入与旧回调 | 用 XML、Java 和 Kotlin 入口验证默认及自定义缩放、配置拒绝和允许的配置调整、原工作线程与终结顺序。验证 File、Assets 和旧自定义 `IFileContainer` 在关闭后能重播；字节流 `MediaDataSource` 路径在 API 23 及以上验证。 |
| 视觉、资源与音频 | 同设备同尺寸对比明暗背景下的透明边缘、VAPX 图文、点击命中、独立遮罩和模糊。验证资源超时及迟到归还、有声 VAPX 的 PCM 输出、静音启动和停止后无声。设备能力不足时记录具体素材和 Codec 错误，另用设备支持的 H.264 素材补测对应路径。 |
