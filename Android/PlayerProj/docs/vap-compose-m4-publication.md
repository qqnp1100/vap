# VAP Compose M4 发布验证

三个模块使用同一发布版本。旧 View 坐标继续为 `io.github.qqnp1100:vap`，新增 `io.github.qqnp1100:vap-core` 和 `io.github.qqnp1100:vap-compose`。View 和 Compose 的发布元数据都引用同版 core，手动复制 AAR 时需要同时提供 core AAR 及相应依赖。

## 本地暂存与消费

在 `Android/PlayerProj` 执行。独立消费工程通过 Maven 坐标解析播放器，SDK 可通过 `ANDROID_HOME` 指定。主工程的统一 Demo 使用项目依赖且 minSdk 为 23。独立 `view-only` 仍以 minSdk 16 验收 View 库消费。

```sh
rtk proxy ./gradlew --offline :publishVapToVerificationRepository
rtk proxy python3 gradle/verify-staged-publication.py build/maven-verification 2.0.31
rtk proxy ./gradlew -p validation/consumer "-PvapRepository=$PWD/build/maven-verification" -PvapVersion=2.0.31 :view-only:assembleDebug :compose-only:assembleDebug :coexist:assembleDebug
rtk proxy ./gradlew -p validation/consumer "-PvapRepository=$PWD/build/maven-verification" -PvapVersion=2.0.31 -PvapPomOnly=true :view-only:assembleDebug :compose-only:assembleDebug :coexist:assembleDebug
```

暂存仓库位于 `build/maven-verification`。验证发布使用独立的无签名 publication。三个模块默认使用 `2.0.31`，也可通过 `-PvapPublishingVersion` 指定统一版本。三个模块分别提供 `centralBundle` 以生成签名包。

## 上传 Maven Central

在 `Android/PlayerProj/local.properties` 配置 `SIGNING_KEY_FILE`、`SIGNING_PASSWORD`、`MAVEN_CENTRAL_USERNAME` 和 `MAVEN_CENTRAL_PASSWORD`。上传凭据使用 Central Portal 用户令牌。

```sh
rtk proxy ./gradlew :publishVapToMavenCentral
```

任务将三个签名产物合并到 `build/central/vap-2.0.31-central.zip` 并上传。Portal 验证完成后，在 Deployments 中点击 Publish。只生成签名 ZIP 时运行 `:vapCentralBundle`。

## 构建与产物检查

| 项目 | 2.0.31 本地结果 |
| --- | --- |
| 产物 | 三组 AAR、sources、javadoc、POM 和 Gradle Module Metadata 齐全。 |
| 依赖 | View 运行时引用同版 core，Compose 的编译及运行时均引用同版 core。两者共同消费时解析为一份 core。 |
| 兼容 | core 与 View 的 minSdk 为 16，Compose 为 23。保留 100 个旧 JVM 类，三个既有生成类差异已在 M1 记录。三份 AAR 共 288 个唯一类文件。 |
| 独立消费 | View-only、Compose-only、共存三个 APK 在 Gradle Module Metadata 和仅 POM 两种模式下均构建成功。View-only 通过 Java 与 XML 使用播放器。 |
| 依赖解析 | View-only 运行时未引入 Compose 或协程，Compose-only 未引入 View。共存解析到唯一的 `vap-core:2.0.31`，重复类检查通过。 |
| Central 签名兼容 | 隔离工程用临时密钥离线生成三套 ZIP，15 个分离签名均通过验证。 |

本轮发布范围为本地验证仓库。43 项 JVM 测试通过，core 与 Compose Lint 无错误。

## 真机入口

```sh
rtk proxy adb shell am start -S -W -n com.tencent.qgame.vap.consumer.viewonly/.MainActivity
rtk proxy adb shell am start -S -W -n com.tencent.qgame.vap.consumer.composeonly/.MainActivity
rtk proxy adb shell am start -S -W -n com.tencent.qgame.vap.consumer.coexist/.MainActivity --es scenario coexist
rtk proxy adb logcat -d -s VapMavenView:I VapMavenCompose:I VapMavenCoexist:I
```

单端应用应收到开始和完成。共存应用在同一页面同时播放，随后单独停止 View、切换 View 素材、移除 Compose Surface 并切换 Compose 素材。每一步核对另一端的播放计数及失败事件。

消费者同时把事件保存到 `files/result.log`。在会过滤 INFO 的设备上，用 `rtk proxy adb shell run-as <package> cat files/result.log` 读取，其中 `<package>` 取上面启动命令中斜杠前的应用 ID。

## API 33 真机消费

在 V2046A（Android 13，API 33）上安装由 Gradle 模块元数据模式构建的三个 APK。安装后核对手机和本地 APK 的 SHA-256 一致。首轮 logcat 采集缺少部分 INFO 事件，最终用应用内持久日志冷启动复跑。

| 消费者 | 结果 | APK SHA-256 |
| --- | --- | --- |
| View-only | Java/XML 入口自然完成，81 次解码回调，收到销毁事件。 | `0f49b7f5b17e8b375347122214ea695b88591992e2517a74f496edcfbb4053e3` |
| Compose-only | 收到开始和自然完成，无失败。 | `83dff14f09bdcc4ee199f8227dd7edbee70535ec402cb7375e6150b64a5806ff` |
| 共存 | 两端同时播放，分别停止、切换源和移除时另一端继续。View 开始 2 次，Compose 开始 3 次、自然完成 1 次，失败 0 次。 | `07c8d3442736b1fc5037db8631f3c093e7845508f3ec3e29ac58fe5c1ac42749` |

[真机原始日志](validation/device-m4-api33-maven-consumers.log)

共存样例验证静音视频播放和 EGL/Surface 隔离。两端同时使用音轨的共存场景，以及此前延期的 API 16–20 兼容测试，仍待设备验收。
