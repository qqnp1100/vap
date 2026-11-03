# PlayerProj Android 性能内存与稳定性分析

后续实施记录：[第一批修复与真机验证](performance-memory-stability-implementation.md)。下文保留审阅时的静态分析口径。

审阅日期：2026 年 11 月 2 日。范围为当前工作区的 `Android/PlayerProj`，交付时核对的基准提交为 `75a87ef2dbf9de6cdc8ac65e9d8cd00436aa5d2a`。本次只新增总结文档，不修改源码、资源和构建配置。

优先处理资源加载无法取消、启动与销毁状态不完整、异常路径资源未释放，以及 Demo 初始化竞态。性能方面，收益最明确的方向是减少旧版 YUV 路径的逐帧分配和复制，以及按实际显示尺寸加载融合图片；随后再优化音频轮询、渲染排队和重复初始化。

## 范围与结论口径

完整阅读了 `animplayer` 的 47 个生产源码文件和 `app` 的 6 个生产源码文件，共 6135 行；核对了构建配置、接口说明、5 个 MP4 素材及图片尺寸。`animtool` 是桌面 Java 素材工具，单独抽查了进程执行、图片处理和 MP4 box 处理，不将其问题计入 Android 播放进程。

这是静态审阅与本地素材检查，没有执行 Android 编译、真机播放、性能采样或故障注入。下文的“代码确认”表示控制流或资源处理缺口可直接从源码确认；“条件风险”表示故障还取决于输入、线程时序或设备。内存数字是明确假设下的计算值，不是测得的峰值或优化收益。

- **P1**：建议优先修复，涉及可触发的崩溃、无法结束的等待、取消失效或关键资源释放缺口。
- **P2**：影响性能、内存、兼容性或播放正确性，需要结合目标设备和业务频率安排。
- **P3**：局部优化或低频问题，排在主链路之后。

## 问题索引

| 编号 | 优先级 | 问题 | 主要后果 |
| --- | --- | --- | --- |
| R01 | P1 | 融合资源无限等待且销毁无法唤醒 | 播放卡死，线程及页面引用滞留 |
| R02 | P1 | 启动阶段拒绝和取消没有完整收尾 | 后续无法播放，停止后仍启动 |
| R03 | P1 | Surface 销毁与异步渲染缺少会话隔离 | 使用失效 Surface，旧任务干扰新播放 |
| R04 | P1 | 初始化失败丢失 Codec 和 Extractor 引用 | 原生资源占用，后续创建失败 |
| R05 | P1 | 清理步骤互相阻断，准备阶段缺少统一释放 | FD、纹理、Bitmap 或音轨未及时回收 |
| R06 | P1 | MP4 配置长度和文件关闭缺少保证 | 大分配、解析失败、FD 占用及首帧延迟 |
| R07 | P2 | YUV 帧由解码线程和渲染线程共享可变字段 | 混帧、漏帧、画面异常 |
| R08 | P2 | YUV 缓冲范围及格式假设不完整 | 数组越界或解码失败，部分设备花屏 |
| R09 | P2 | 音频缓冲变更和输出格式处理不完整 | 声音中断、错误 PCM、音画不同步 |
| R10 | P1 | 对外回调异常可逃出工作线程任务 | 进程崩溃或释放中断 |
| R11 | P2 | Assets 和 Stream 容器不能可靠重复使用 | 自动恢复或再次播放失败 |
| R12 | P2 | 遮罩纹理在调用线程直接删除 | GPU 资源滞留、遮罩更新异常 |
| R13 | P2 | GLES 2.0 纹理参数和 Surface 采样兼容性缺口 | 特定设备黑图、错位或裁剪异常 |
| D01 | P1 | Demo 在异步初始化前访问 lateinit 字段 | 页面快速退出时直接崩溃 |
| D02 | P2 | Demo 文件任务缺少生命周期和失败管理 | 已退出页面继续播放、残缺缓存及 I/O 浪费 |
| P01 | P2 | YUV 每帧数组分配、复制和纹理重定义 | GC 压力与上传开销 |
| P02 | P2 | 融合图片尺寸偏大且始终生成 mipmap | Bitmap 和纹理占用偏高 |
| P03 | P2 | 渲染任务未限流，融合帧索引采用推测 | 延迟积累，图层与视频不同步 |
| P04 | P2 | 音频零等待轮询和逐块 PCM 分配 | CPU 空转与短命对象 |
| P05 | P2 | 重复创建渲染环境及保留较重对象 | 首帧开销、线程和内存成本 |
| P06 | P3 | 每帧小对象、字符串及固定绘制状态重复处理 | 可进一步减少 CPU 和分配 |

## 播放链路与资源归属

`AnimView.startPlay` 在主线程检查状态；`AnimPlayer` 将 MP4 配置解析、融合资源获取放到渲染线程；准备 Surface 后，`HardDecoder` 在渲染线程创建 EGL 和视频 Codec，在视频解码线程送入压缩数据。普通路径通过 SurfaceTexture 取出解码纹理，再绘制视频、融合图层和遮罩；旧版特殊尺寸路径在 CPU 上拆分 YUV 后上传三个平面。音频有独立线程、Extractor、Codec 和 AudioTrack。

当前停止主要依赖跨线程布尔标志，销毁依赖向渲染线程追加任务；文件关闭、插件释放、GL 释放和线程退出分别位于不同层。建议把“一次播放”定义为明确的会话：准备、播放、停止和失败都进入同一条可取消、幂等的终结流程，GL 对象由对应的渲染线程管理，资源回调携带会话标识。

## 稳定性与资源释放

### R01 融合资源等待无法可靠结束

**P1，代码确认。** [MixAnimPlugin.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mix/MixAnimPlugin.kt:163) 在 `fetchResourceSync` 中通过无超时的 `lock.wait()` 等待全部资源回调。该调用发生在 `AnimPlayer.startPlay` 投递的渲染线程任务里。

只要一个 `fetchImage` 或 `fetchText` 不回调，播放就无法继续。`stopPlay` 只设置解码停止标志，不能唤醒资源等待；销毁时，[HardDecoder.destroyInner](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:387) 又把插件销毁投递到同一条已被阻塞的渲染线程。真正设置 `forceStopLock` 的插件 `destroy()` 因此无法执行。页面退出后，仍可能通过运行中的调用栈保留播放器、View 和业务资源回调。

另外，回调计数没有按资源去重：同一资源重复回调可提前满足总数；旧会话的迟到回调也没有隔离。Demo 注释要求业务“必须回调”，但库仍应处理网络失败、取消和接入错误。

**建议：** 改为异步汇聚资源，设定超时和明确失败结果；取消信号不能依赖被等待线程执行。每个资源只接受一次结果，迟到回调核对会话并释放不再使用的资源。验收时分别模拟不回调、重复回调、销毁后回调，确认播放器能结束且新会话不受影响。此处是工作线程卡死，不应直接写成已证实的主线程 ANR。

### R02 启动阶段的拒绝与停止缺少状态收尾

**P1，代码确认。** [AnimPlayer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimPlayer.kt:79) 将 `isStartRunning` 置为 `true`，但 `onVideoConfigReady` 返回 `false` 后只打印日志，没有复位状态、释放已经获取的资源或完成本次会话。之后 `AnimView` 会持续按“正在播放”拒绝新请求。

准备过程中调用 `stopPlay()` 也不会取消解析或清除 `startRunnable`。解析或 Surface 准备稍后完成时，仍可进入 `innerStartPlay`；[HardDecoder.start](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:53) 又将停止标志重置为 `false`，覆盖此前的取消意图。

**建议：** 拒绝配置、停止准备、Surface 尚未就绪、解析失败均进入统一终结流程；清除待启动任务和准备 View 的任务，复位状态，取消资源请求。验收覆盖“配置回调返回 false 后再次播放”“资源加载中停止”“布局完成前停止”。

### R03 Surface 销毁和新旧播放之间存在竞态

**P1，条件风险，线程时序缺口已确认。** [AnimView.onSurfaceTextureDestroyed](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimView.kt:136) 发起异步销毁后立即返回 `true`。而 [HardDecoder.renderData](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:68) 已排队的任务执行时没有再次检查停止状态和 Surface 所属会话，释放流程还会调用 `clearFrame()` 和交换缓冲。

Android 对此回调的要求是：返回 `true` 后不能继续向该 SurfaceTexture 渲染。当前时序可能在 Surface 被释放后仍执行绘制，出现 EGL 失败或黑屏；不能仅据此断言必然发生原生崩溃。[Android SurfaceTextureListener 文档](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener#onSurfaceTextureDestroyed(android.graphics.SurfaceTexture))

同一方法稍后执行的 UI 清理直接读取当前 `innerTextureView` 并 `removeAllViews()`，没有绑定被销毁的旧 View。快速重播时，旧任务可能清除新 View。`isRunning`、`isStopReq`、`needDestroy` 等字段也跨线程直接读写，没有统一线程归属或完整同步。

**建议：** 使用会话编号和明确状态机过滤过期任务；在销毁时停止产帧并丢弃待绘制任务。若选择返回 `false` 接管 SurfaceTexture，则在渲染线程完成清理后显式释放，避免在主线程长时间等待。UI 清理应只操作对应的旧 View。循环执行播放、停止、detach、attach，并校验每次完成回调和资源释放次数。

### R04 初始化异常会丢失已创建资源的引用

**P1，代码确认，故障由平台调用失败触发。** 存在三类明确缺口：

- [HardDecoder.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:156) 在 `decoder = MediaCodec.createDecoderByType(...).apply { configure(); start() }` 完成后才赋值。`configure` 或 `start` 抛异常时，外部 `decoder` 仍为 `null`，异常分支无法释放刚创建的 Codec。[AudioPlayer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AudioPlayer.kt:86) 同样在配置、启动后才保存到字段。
- [MediaUtil.getExtractor](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/MediaUtil.kt:36) 创建 Extractor 后直接调用 `setDataSource`，失败时既不释放也不把对象交给调用者。
- [HardDecoder.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:100) 在不支持 HEVC、渲染器创建失败两个分支调用 `release(null, null)`，遗漏了此前已创建的 Extractor。

此外，Render 构造期间先初始化 EGL，再编译 shader；如果构造中途抛异常，`Decoder.render` 尚未保存实例，EGL 的局部初始化资源也缺少回滚。

**建议：** 资源创建成功后立即建立可清理的所有权，配置和启动分别执行；失败按创建的逆序释放，并区分“已创建”和“已启动”，不能要求 `stop()` 成功后才执行 `release()`。以非法媒体、不可用 Codec、EGL/shader 初始化失败连续重试，观察 FD、Codec 和原生内存是否恢复。

### R05 单个释放异常会阻断后续清理

**P1，代码确认。** [HardDecoder.release](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:350) 将 Codec、Extractor、SurfaceTexture、插件、纹理和 Surface 的清理放在一个 `try` 中；[AudioPlayer.release](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AudioPlayer.kt:157) 也采用相同方式。前面的 `stop()` 或业务 `releaseResource()` 一旦抛异常，后面的资源就不会释放。

[MixAnimPlugin.destroy](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mix/MixAnimPlugin.kt:128) 在业务资源释放回调之后才清空映射；回调异常会保留内部数据。解析阶段已经加载了图片、随后配置校验失败时，`AnimPlayer` 只回调失败和完成，未保证调用插件释放；如果尚未创建 TextureView，就不能依赖其销毁回调补做清理。

**建议：** 各资源独立执行清理，使用 `finally` 保证引用和状态复位；释放应幂等，错误分别记录，业务回调放在内部状态收尾之后。为所有准备阶段出口补上资源回收。验证一个资源清理失败时，其他资源仍能释放。

### R06 配置解析缺少长度预算和确定性文件关闭

**P1，代码确认。** [AnimConfigManager.parse](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimConfigManager.kt:117) 使用文件里的有符号 `Int` 长度直接分配 `ByteArray(head.length - 8)`，没有检查最小长度、文件剩余长度或配置大小上限；也没有处理扩展 box 长度，未检查 `read` 和 `skip` 是否达到请求值。损坏或过大的配置可触发负长度异常、大量分配或解析错误。外层捕获了 `Throwable`，因此这些异常通常转为配置失败，不能全部算作未捕获崩溃；大分配仍会造成显著内存压力。

`closeRandomRead()` 只位于找到 `vapc` 并成功读完的分支。未找到配置或读取异常时不会关闭；[FileContainer.close](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/file/FileContainer.kt:42) 又是空实现，所以 `AnimView.hide()` 不能补救 RandomAccessFile 的关闭。反复播放旧版素材或解析失败会造成 FD 非确定性回收。

**实际素材证据：** `special_size_750.mp4` 没有 `vapc`，文件偏移 143351 起存在大量零字节。按当前 FileContainer 的读取和跳过语义复核，解析器会执行 **303900 次成功的 8 字节头读取**，其中 **303896 次长度为零**；其他四个带配置的样例均在第 2 次读取命中 `vapc`。这是本地字节扫描结果，未据此推断真机耗时。问题是反复小读和对象创建，不是无限循环，因为每轮读取仍推进 8 字节。

**建议：** 用 `try/finally` 统一关闭，`close()` 也负责幂等释放随机读句柄；用 `Long` 检查 box 边界和整数溢出，正确处理特殊长度，不允许对无效头继续逐块扫描；使用完整读取和可验证的跳过。为配置字节数、资源数、帧数、视频和 Bitmap 尺寸设置预算。[AnimConfig.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimConfig.kt:55) 当前也未验证宽高及矩形范围。注意坐标工具采用浮点除法，零尺寸主要产生 NaN、Infinity 和画面异常，并非必然的整数除零崩溃。

### R07 YUV 帧没有明确的跨线程所有权

**P2，条件风险，共享字段读写已确认。** [HardDecoder.yuvProcess](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:300) 在解码线程调用 `render.setYUVData()`，随后才向渲染线程投递绘制任务。[YUVRender.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/YUVRender.kt:149) 把宽高和 Y、U、V 分别写入实例字段；绘制完成又逐个 `clear()` 并置空。

如果下一帧在当前帧绘制时到达，渲染线程可能组合不同帧的平面，或把刚提交的新帧字段清空。Handler 的投递可以发布投递前的数据，但不能保护后续帧对同一字段的覆盖。渲染越慢越容易暴露问题。

**建议：** 用一个包含尺寸、时间戳、帧索引和三个平面的帧对象提交；取得后由渲染线程独占，到上传结束才归还缓冲池。使用有界队列或双缓冲并明确丢帧策略，不能在未解决所有权前直接把数组改成全局复用。验收时人为放慢渲染，检查平面一致性和丢帧行为。

### R08 YUV 输出的范围和布局假设过强

**P2，代码确认，具体故障取决于 Codec 输出。** [HardDecoder.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:300) 将输出缓冲 `position` 重置为 0，只将 `limit` 设置为 `offset + size`；非零 offset 时会把有效数据前的字节也当作 YUV。随后按 `alignWidth × alignHeight` 推算 Y、U、V 起点，没有验证每个平面实际可读范围。

`yuvCopy()` 逐行复制固定 `outWidth` 字节，未检查 stride、slice-height、裁剪区域与数组长度是否匹配；`yuv420spTop()` 也假定完整连续的半平面数据。非标准布局、截断输出、奇数尺寸或色彩格式不符时，可出现 `ArrayIndexOutOfBoundsException`、缓冲范围异常或花屏。这些调用位于解码异常捕获范围，通常表现为播放失败，而不是直接未捕获崩溃。

**建议：** 严格使用输出有效范围，校验尺寸乘积和复制边界；依据实际输出格式处理 stride、裁剪和色彩布局，不将“非半平面格式”一律按平面格式解释。API 21 及以上可评估按索引获取 Buffer/Image 并按 plane stride 读取，低版本保留明确支持的格式分支；不支持时返回可识别错误。相关输出缓冲约束见 [MediaCodec 文档](https://developer.android.com/reference/android/media/MediaCodec#getOutputBuffer(int))。

### R09 音频输出缓冲和循环状态处理不完整

**P2，代码确认，旧系统和特定 Codec 更容易触发。** [AudioPlayer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AudioPlayer.kt:92) 缓存 `decoder.outputBuffers`，但仅在 `INFO_OUTPUT_FORMAT_CHANGED` 时刷新，没有处理 `INFO_OUTPUT_BUFFERS_CHANGED`。继续用旧数组可能访问失效缓冲或不匹配的索引。Android 官方旧版同步解码示例明确要求在缓冲变更事件中重新获取数组。[MediaCodec 缓冲数组说明](https://developer.android.com/reference/android/media/MediaCodec#getOutputBuffers())

当前读取 PCM 未显式设置 `bufferInfo.offset` 和有效范围。API 21 起框架会为已出队缓冲设置有效 position/limit，因此不能说所有版本均读错；本项目 `minSdk = 16`，低版本分支仍需处理。输出格式变化时也未更新 AudioTrack 的采样率、声道和 PCM 格式，`AudioTrack.write()` 的返回值及错误没有处理。

循环方面，[HardDecoder.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:251) 消耗一次视频循环后通过 `player.playLoop` 同时改写音频剩余次数，而音频 EOS 又自行 `--playLoop`。如果视频先结束，两边会重复消耗同一个循环额度，可能导致音频提前结束。音频送入 Codec 的时间戳始终为 0，也没有与视频播放时钟建立关联。

**建议：** 分清缓冲变更和格式变更；新 API 按索引取缓冲，旧 API 正确刷新数组并设置范围；按实际输出格式配置 AudioTrack，处理短写和错误。循环次数由单一控制器管理，音视频独立报告一轮完成。用音频略长于视频的素材播放 2～3 轮，并覆盖音视频任一方先结束。

### R10 业务回调抛异常时可导致进程崩溃

**P1，条件风险，异常边界缺口已确认。** [AnimPlayer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimPlayer.kt:89) 的 Handler 任务直接调用 `onVideoConfigReady`，该调用不在配置解析的 `try/catch` 内。[HardDecoder.release](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:370) 的 `onVideoComplete` 和 `destroyInner` 的 `onVideoDestroy` 也缺少外围保护；[Decoder.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/Decoder.kt:145) 直接转发给接入方。

业务回调中抛出的运行时异常可以逃出 Handler 任务，并触发 Android 的未捕获异常处理；此外，`onVideoStart/onVideoRender` 虽处于解码异常捕获范围，异常处理中的 `onFailed` 若再次抛异常，也会阻断释放。不能把所有失败都归因于 Codec。

**建议：** 对可恢复的接入方异常设置清晰边界，先完成内部状态和资源收尾，再通知业务；不要笼统吞掉所有系统级 `Error`。保持工作线程回调的现有约定并明确文档，业务 UI 操作需切回主线程。注入分别抛异常的配置、结束、错误和资源释放回调，验证后续资源仍能回收。

### R11 文件容器不满足重播和自动恢复语义

**P2，代码确认。** [AnimView.onAttachedToWindow](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimView.kt:166) 会复用 `lastFile` 恢复未完成循环。但 [AssetsFileContainer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/file/AssetsFileContainer.kt:30) 在构造时创建固定的输入流，解析完成便关闭它，`startRandomRead()` 不重开；整个会话结束后 FD 也关闭。复用同一对象时，读取已关闭的流或 FD 会失败。

[StreamContainer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/file/StreamContainer.kt:24) 的 `startRandomRead()` 同样不重置读取位置，重播会从上次解析结束的位置继续找配置。另一个入口问题是 `startPlay(assetManager, path)` 先打开容器，随后才在 UI 任务里检查“不可见/正在播放”；拒绝请求时没有关闭刚创建的容器。

**建议：** 明确容器是一次性资源还是可重复打开的数据源。恢复播放时创建新会话容器；可重用实现必须重新打开或 reset，关闭操作保持幂等。请求被拒绝时也要明确容器释放责任。测试 File、Assets、Stream 三类来源的同对象重播、detach/attach 和重复启动。

### R12 遮罩纹理更新没有固定在 GL 线程

**P2，条件风险。** [AnimView.updateMaskConfig](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimView.kt:214) 直接进入 [AnimPlayer.updateMaskConfig](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimPlayer.kt:150)，再调用 [MaskConfig.safeSetMaskBitmapAndReleasePre](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mask/MaskConfig.kt:47) 删除 GL 纹理。调用方可能是主线程，或者尚未建立 EGL 的配置回调线程，无法保证拥有该纹理的 current context。

如果旧纹理未被正确删除，代码仍把 ID 清零；随后渲染线程上传新纹理，旧对象可能一直保留到 EGL context 销毁。`MaskConfig.release()` 仅清空 Bitmap 和坐标，不删除纹理，也不重置 ID。频繁更新遮罩时应重点观察 GPU 内存。

**建议：** Bitmap 和坐标作为一次完整更新投递到渲染线程，在同一 context 中上传、替换和删除纹理；定义 Bitmap 的共享、复制及回收责任，避免调用方在上传期间 recycle。释放遮罩时统一删除纹理并清零。上下文销毁本身可回收其 GL 对象，因此这里不是所有播放场景都会永久泄漏。

### R13 纹理和 Surface 采样存在设备兼容风险

**P2，条件风险。** [YUVRender.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/YUVRender.kt:99) 对 YUV 纹理设置 `GL_REPEAT`；[TextureLoadUtil.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/TextureLoadUtil.kt:42) 对图片始终设置 mipmap 过滤并生成 mipmap，没有设置二维纹理的 wrap。工程创建 GLES 2.0 context；在不支持完整 NPOT 扩展的实现上，非 2 的幂尺寸纹理需要 `CLAMP_TO_EDGE` 和非 mipmap 过滤，否则采样可能返回黑色。750×814 的 YUV 和 336×44 的文字纹理都属于相关输入。[Khronos OpenGL ES 2.0 规范 3.8.2 节](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf)

[MaskRender.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mask/MaskRender.kt:89) 绑定的是 `GL_TEXTURE_2D`，设置 wrap 时却使用 `GL_TEXTURE_EXTERNAL_OES`，没有正确配置目标遮罩纹理。建议统一纹理目标，并按设备能力和缩小需求决定是否使用 mipmap。

普通视频路径每次 `updateTexImage()` 后没有读取 SurfaceTexture 的 transform matrix，直接用配置坐标采样。官方要求使用该矩阵映射到实际纹理采样位置；设备输出包含裁剪或变换时，当前实现可能错位。修复时需一并验证 RGB、alpha 和融合遮罩坐标，不能仅在某一层叠加变换。[SurfaceTexture 采样说明](https://developer.android.com/reference/android/graphics/SurfaceTexture)

[EGLUtil.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/EGLUtil.kt:43) 对初始化或 `eglMakeCurrent` 失败主要记录日志，`swapBuffers()` 也不检查结果。建议把这些失败转成可识别的播放错误，并停止继续绘制和清理部分初始化资源，避免长期黑屏而无明确原因。

## Demo 的崩溃与文件任务

### D01 异步初始化前进入 onPause 会访问未初始化字段

**P1，代码确认。** 四个 Demo 的 `animView` 都是 `lateinit`，只在文件拷贝完成后的 `init()` 中赋值，`onPause()` 却直接调用 `animView.stopPlay()`：

| Activity | 赋值位置 | 未保护访问 |
| --- | --- | --- |
| AnimSimpleDemoActivity | [第 77 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimSimpleDemoActivity.kt:77) | [第 157 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimSimpleDemoActivity.kt:157) |
| AnimSpecialSizeDemoActivity | [第 78 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimSpecialSizeDemoActivity.kt:78) | [第 170 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimSpecialSizeDemoActivity.kt:170) |
| AnimActiveDemoActivity | [第 105 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimActiveDemoActivity.kt:105) | [第 186 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimActiveDemoActivity.kt:186) |
| AnimVapxDemoActivity | [第 90 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimVapxDemoActivity.kt:90) | [第 230 行](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimVapxDemoActivity.kt:230) |

首次进入后快速按 Home、返回或切换页面，或者拷贝失败导致 `init()` 根本不执行，即可满足 `UninitializedPropertyAccessException` 的触发条件。

**建议：** `setContentView` 后立即获取播放器 View，将“View 已初始化”和“素材已就绪”分开；生命周期方法使用已建立的引用或明确检查初始化状态。验收覆盖首次安装、慢 I/O、拷贝失败和快速退出。

### D02 文件任务完成后可能重新启动已退出页面

**P2，代码确认。** [FileUtil.copyAssetsToStorage](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/FileUtil.kt:26) 使用裸线程；各 Demo 完成拷贝后直接 `post { init() }`，没有取消句柄或页面状态检查。MD5 校验也每次新建线程，校验完成直接开始播放。页面已暂停或销毁时，旧回调仍可能启动播放器，延长 Activity 引用的存活。

文件拷贝异常路径没有 `finally/use` 关闭已打开的流；直接写最终路径，发生部分写入后，下次只按“文件存在”跳过，可能永久保留残缺文件。每次点击播放都重新读取整个文件做 MD5，会增加重复 I/O，快速连点还会并发校验同一素材。

**建议：** 将任务绑定到页面或播放会话，结束时取消，回调时检查会话和页面状态；固定规模执行 I/O。先写临时文件，成功校验后替换正式文件，所有流用 `use` 关闭。缓存已验证文件的结果，但文件内容或版本改变后必须失效，保留下载完成时的完整性校验。

Active Demo 在 `onPause` 发出异步停止后，[onStop](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimActiveDemoActivity.kt:78) 立即 recycle 遮罩 Bitmap，而渲染清理不保证完成。应按 R12 明确 Bitmap 所有权与停止完成时点，避免上传和回收交叉。

## 性能与内存优化

### P01 优先降低旧版 YUV 路径的分配与复制

**P2，确定存在开销，收益需实测。** [HardDecoder.yuvProcess](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:300) 每帧分配整帧 `yuvData` 和 Y、U、V 三个数组；半平面转平面还分配一次整帧数组。忽略 stride padding 和对象头，分配量约为：

| 路径 | 每帧数组字节数 | 750×814 素材的每秒分配估算 |
| --- | --- | --- |
| 平面 YUV | `1.5WH + WH + 0.25WH + 0.25WH = 3WH` | 约 41.9 MiB/s |
| 半平面再转平面 | `3WH + 1.5WH = 4.5WH` | 约 62.8 MiB/s |
| 上传 GPU 的三个平面 | `1.5WH` | 约 20.9 MiB/s 数据量 |

计算使用样例 `special_size_750.mp4` 的 750×814 和 `24000/1001 ≈ 23.976 fps`，来自本地 `ffprobe`。实际分配还受输出对齐和 offset 影响；表中分配速率不等于同时驻留内存，也不表示已经发生 OOM。该路径只在 `enableVersion1` 且视频宽度不能被 16 整除时启用，不能推广到所有 VAP 播放。

[YUVRender.draw](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/YUVRender.kt:162) 每帧三次 `glTexImage2D` 重定义纹理，并创建 Buffer 包装对象。驱动是否重复分配存储依实现而定，但这里可以明确减少 API 和数据整理开销。

**建议顺序：** 先修复 R07 的帧所有权，再采用固定容量的双缓冲或缓冲池；尽量按行从有效输出范围直接复制到目标平面，合并半平面转换和裁剪，消除中间整帧数组；纹理在尺寸变化时分配，普通帧用 `glTexSubImage2D` 更新。长期可通过验证 SurfaceTexture 裁剪与变换方案减少 CPU YUV 回退，保留经过验证的设备兼容分支。对比 Java 分配率、GC 次数、解码和上传耗时，而非只看平均 FPS。

### P02 按实际显示尺寸解码融合图片并控制 mipmap

**P2，已有样例可直接优化。** [AnimVapxDemoActivity.fetchImage](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/app/src/main/java/com/tencent/qgame/playerproj/player/AnimVapxDemoActivity.kt:101) 设置 `inScaled = false` 后解码完整原图，没有按目标尺寸采样。素材 `vapx.mp4` 的图片资源配置为 66×66，全部融合帧中的最大区域为 **79×80 个素材坐标单位**；原图尺寸则明显更大。

| 图片 | 原始尺寸 | Bitmap 像素估算 | GPU 纹理含完整 mipmap 估算 | 两者合计 |
| --- | --- | --- | --- | --- |
| head1.png | 860×858 | 2.81 MiB | 3.75 MiB | 6.57 MiB |
| head2.png | 740×738 | 2.08 MiB | 2.78 MiB | 4.86 MiB |

按 Bitmap 和 GPU 均为每像素 4 字节、mipmap 约为基础纹理的 4/3 计算，不含驱动对齐和其他缓存。Demo 每次选择其中一张头像，不能将两行直接相加当作一次播放的必需占用。

**建议：** 目标解码尺寸根据“各帧最大显示区域 × View 显示缩放比例 × 合理采样余量”确定，不能简单固定为 66×66；使用尺寸探测和 `inSampleSize` 或具备目标尺寸能力的图片加载方式。当前 [Resource](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mix/Resource.kt:24) 未暴露目标宽高，后续可扩展资源描述供接入方决策。[Android 大图采样说明](https://developer.android.com/topic/performance/graphics/load-bitmap)

`TextureLoadUtil` 对文字、图片、遮罩都生成 mipmap。对几乎不缩小的文字或遮罩可关闭；存在明显缩小时按画质需求保留，不能为了省内存统一删除。小图采样、避免不必要的 mipmap，以及 GPU 上传完成后的 CPU Bitmap 生命周期管理，应一并评估。

库自己创建的文字 Bitmap 可以在不再需要重传时释放；业务提供的图片必须遵守明确的所有权协议，不能无条件 recycle 共享图片。缓存应有字节上限，避免用无限缓存替代重复解码。

### P03 限制待绘制任务并以实际帧信息对齐融合图层

**P2，条件性性能与正确性问题。** [HardDecoder.renderData](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:68) 为帧事件继续投递 Runnable，没有应用层待绘制上限或合并策略。渲染慢时，任务可能积压，停止和释放也排在后面。SurfaceTexture 更新取到的图像与“第几个渲染 Runnable”不一定一一对应。

[AnimPluginManager.onRendering](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/plugin/AnimPluginManager.kt:75) 用跨线程的 `decodeIndex`、`frameIndex` 和连续偏差次数追赶，而没有将视频图像与融合帧元数据绑定。漏帧或重复绘制时可能造成融合图层抖动、遮罩与主体不同步。

**建议：** 普通纹理路径合并重复待绘制请求，停止后丢弃过期任务；结合 SurfaceTexture 实际时间戳与已提交解码帧的映射确定融合帧。YUV 使用 R07 的有界帧队列，明确是保持全部帧还是优先保持实时性。记录待绘制数量、实际呈现时间与素材帧索引，不能仅用 `onVideoRender` 次数作为上屏 FPS，因为它当前由解码线程触发。

### P04 降低音频轮询和 PCM 分配开销

**P2，确定存在可优化路径。** [AudioPlayer.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AudioPlayer.kt:109) 输入最多等待 1 ms，输出使用 `dequeueOutputBuffer(..., 0)`。输入 EOS 后不再等待输入，如果输出暂时未就绪，循环没有任何等待，可形成短时间 CPU 空转。每个输出块都会分配一个 `ByteArray(bufferInfo.size)`，再复制到 AudioTrack。

**建议：** 采用合理的阻塞等待或版本适配后的异步解码，兼顾停止响应；新 API 可评估直接向 AudioTrack 写入 ByteBuffer，旧 API 复用 PCM 缓冲，并处理写入结果。无音轨素材不应反复启动音频解码线程和第二个 Extractor；可在已有媒体探测中记录是否存在音轨，或由明确知道无音轨的调用方设为静音。本次样例中 `demo.mp4` 和两个 mask 素材均无音轨。

验证时观察音频线程 CPU 时间、EOS 到释放的耗时、PCM 分配率和音轨 underrun；不要只缩短轮询超时。

### P05 控制重复初始化和播放后的对象保留

**P2。** [AnimView.hide](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AnimView.kt:300) 在完成回调时移除内部 View，引发 Surface/EGL 销毁；下一次播放重新创建 TextureView、EGL、shader 和纹理。对连续礼物、消息特效队列，这些操作会进入每次播放的首帧路径。

线程退出却只在 `isDetachedFromWindow` 时执行：[Decoder.destroyThread](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/Decoder.kt:127)、[AudioPlayer.destroyInner](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/AudioPlayer.kt:191)。因此已停止但仍挂载的播放器可保留工作线程。应限制同时存在的活跃播放器数，并按业务频率决定短期复用还是空闲回收，不能把所有长期持有的线程一概认定为泄漏。

另有几处内存保留或多实例开销：

- `lastFile` 在关闭后未置空；对 [StreamContainer](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/file/StreamContainer.kt:24)，整个 MP4 字节数组仍可随 View 存活。音视频数据源引用的是同一数组，不能据此声称拷贝了两份 MP4。
- `AnimConfig.jsonConfig` 与解析出的帧、资源结构在播放期间并存，结束后 config 仍保留；对大配置应限制规模，并在确认外部无需访问原始 JSON 后及时释放或只保存必要数据。
- [StreamMediaDataSource](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/file/StreamMediaDataSource.kt:28) 用类级锁保护整个 `arraycopy` 和 `getSize`，会串行化不同播放器的读取。若字节数据所有权保证只读，可移除全局互斥或缩小到必要范围。
- [ShaderUtil](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/ShaderUtil.kt:25) 成功链接后没有释放 shader，正常销毁也未主动删除 program；[TextureLoadUtil](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/TextureLoadUtil.kt:24) 先生成纹理再检查 Bitmap，发现已 recycle 时直接返回 0，遗漏生成的 ID。对象一般最终随 context 销毁而回收，但应在确定不再使用时释放，尤其要覆盖初始化失败和长寿命 context。

**建议：** 先修复生命周期与释放，再为高频播放加入有上限的 EGL/资源复用。复用前必须重新初始化已删除的纹理、校验 Surface 有效性，并处理上下文丢失。定义“最终释放”接口或状态，解除文件、配置和业务监听引用；保留自动恢复功能时，仅保留轻量且可重新打开的来源描述。

### P06 减少热路径中的小额重复工作

**P3，需在主要瓶颈处理后衡量。** [MixRender.transColor](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mix/MixRender.kt:141) 为文字每次绘制创建 `FloatArray(4)`；部分坐标计算创建 `PointRect`；YUV 绘制反复包装常量 FloatBuffer。[HardDecoder](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/HardDecoder.kt:216) 等处先构造逐帧日志字符串，再由 `ALog.d` 检查调试开关，关闭日志仍保留字符串构造成本。

**建议：** 缓存颜色分量、常量 Buffer 和不变几何，日志使用调用前开关或延迟构造。现有 `GlFloatArray` 已复用数组和 direct buffer，应保留这部分设计。固定纹理参数尽量在初始化或更新时设置；融合绘制的纹理切换与混合开关可按实测热点合并。

[MaskShader](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mask/MaskShader.kt:35) 开启模糊后采用 3×3 采样。对不变的遮罩可评估在资源更新时预处理，降低每帧片元工作量；是否值得取决于屏幕覆盖面积和更新频率，不能仅按采样次数声称 GPU 耗时减少固定比例。

## 其他可整改项

| 优先级 | 位置与问题 | 建议 |
| --- | --- | --- |
| P2 | [SpeedControlUtil.kt](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/SpeedControlUtil.kt:32)：设置 `fps <= 0` 时直接返回，无法清除上一视频的固定帧间隔；`reset()` 不重置该值和 `loopReset` | 按会话重置时钟状态，恢复“使用素材时间戳”的语义 |
| P2 | [SpeedControlUtil.preRender](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/SpeedControlUtil.kt:57)：睡眠循环不检查停止，捕获中断后继续等；异常时间戳可使停止延迟数秒 | 将等待设计为可取消，检查会话终止；这属于解码线程停止延迟，不直接等同主线程 ANR |
| P2 | [YUVRender](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/YUVRender.kt:27) 未实现 `updateViewPort`，继承的是空实现；普通 Render 有实现 | 为 YUV 覆盖尺寸更新，验证播放期间容器缩放和旋转后的视口 |
| P2 | [MediaUtil.checkSupportCodec](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/util/MediaUtil.kt:79) 只检查 MIME，没有验证实际素材尺寸、profile 和 level | 能力探测按 API 版本分支，并保留 configure 失败的正确释放和错误上报；不是所有 HEVC MIME 支持都代表素材可播放 |
| P3 | [MaskConfig.equals](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animplayer/src/main/java/com/tencent/qgame/animplayer/mask/MaskConfig.kt:62) 对各字段使用 `!=` 和 `&&`，连对象与自身比较也返回 false | 修正 equals/hashCode 契约；当前播放主链路未发现依赖该比较，暂不归为已触发的播放故障 |

## 建议执行顺序与验证标准

| 阶段 | 建议处理范围 | 核心验收点 |
| --- | --- | --- |
| 第一阶段 | R01、R02、R03、R04、R05、R06、R10、D01 | 所有启动出口可终结；取消后不再启动；资源缺失和业务回调异常可控；页面快速退出不崩溃 |
| 第二阶段 | R07、R08、P01、P02 | YUV 帧所有权清晰，越界输入被拒绝；分配率下降；图片采样后视觉质量可接受 |
| 第三阶段 | R09、R11、R12、R13、P03、P04 | 多来源恢复正常，音视频循环一致，Surface/遮罩更新稳定，目标设备纹理兼容 |
| 第四阶段 | P05、P06、D02 与其他整改项 | 高频播放首帧耗时降低，多实例资源占用有上限，长时间运行后无持续增长 |

基准测试应使用相同设备、素材、显示面积、音频和遮罩开关，分别记录首帧时间的中位数与高分位、实际呈现帧时间、CPU 时间、Java 分配率、GC、进程内存、FD、工作线程和 GPU 资源。应区分初始化峰值、播放稳态和完全销毁后的残留，不把 JVM 堆曲线当作全部视频内存。现有两个测试文件均只有 `2 + 2 == 4`，无法覆盖上述风险。

| 验证场景 | 具体输入或动作 | 重点观察 |
| --- | --- | --- |
| 资源回调异常 | 不回调、回调两次、返回 null、退出后回调 | 超时与取消有效；资源只计数一次；旧会话不写入新会话 |
| 启动中取消 | 慢资源、尚未完成布局、配置回调返回 false 后重播 | `isRunning` 恢复；无迟到启动；资源释放完整 |
| 页面生命周期 | 首次安装后快速返回/Home；反复 detach/attach；连续重播 | Demo 无 lateinit 崩溃；旧 UI 清理不删除新 View |
| 文件异常 | 无 vapc、截断头/内容、超大或负长度、零长度、短读与短 skip | 有限时间失败；不进行不受控分配；FD 恢复 |
| 媒体初始化失败 | 不支持的格式、Codec 配置失败、EGL/shader 创建失败 | 已创建资源能独立释放；错误原因可区分 |
| 旧版 YUV | 750×814、不同 stride/crop、平面/半平面、故意放慢渲染 | 无越界和混帧；缓冲池容量稳定；上传耗时可控 |
| 音频与循环 | 无音轨、2～3 次循环、音频较长/视频较长、输出格式变更 | 正确处理缓冲事件；循环次数一致；无 EOS 忙等 |
| 资源所有权 | 大图、业务共享 Bitmap、停止时回收、连续更新遮罩 | 上传与 recycle 不交叉；GL 删除在正确线程 |
| 设备兼容 | GLES 2.0 NPOT 能力较弱设备、不同 Codec、API 16～20 分支 | 无黑图，缓冲 offset 处理正确，降级可识别 |
| 连续与多实例播放 | 固定次数反复播放/销毁，逐步增加同时播放数量 | 内存、FD、线程在规定生命周期后回落，不随轮数持续增长 |

## animtool 附注

这些问题只影响桌面素材制作工具，不应归因于 Android 播放库。

- **MP4 box 定位错误。** [Mp4BoxTool.parse](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animtool/src/main/java/com/tencent/qgame/playerproj/animtool/Mp4BoxTool.java:95) 扫描下一 box 使用 `seek(head.length)` 作为绝对位置，未累加当前 box 的起始偏移。`vapc` 不在前几个预期位置时可能读错或反复跳转；未命中时也未严格校验最终 `head.type`。应使用绝对累计偏移、边界检查和统一关闭。
- **外部进程日志没有内存上限。** [ProcessUtil.java](/Users/xzin/workplace/androidExt/vap/Android/PlayerProj/animtool/src/main/java/com/tencent/qgame/playerproj/animtool/ProcessUtil.java:15) 把 stdout/stderr 全部累积到 StringBuilder，`waitFor()` 没有超时，返回前也不等待两个读取线程结束。长时间转码、日志量大或进程卡住时，会扩大堆占用或阻塞工具流程。建议日志流式落盘或保留有限尾部，给进程增加超时和取消，并关闭流、等待读取线程收尾。

## 交付核对

本次依据当前源码、素材字节结构、`ffprobe` 元数据、PNG 头部尺寸及 Android/Khronos 官方 API 约束整理。代码链接对应审阅时工作区行号；后续修改后需随代码更新。报告中的修复和验证均为后续建议，本次没有实施源码修改，也没有把静态推导标为已完成的真机复现。
