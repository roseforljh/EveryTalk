# EveryTalk 全项目审查报告

审查时间：2026-06-09

## 覆盖范围

- Gradle、Wrapper、版本目录、仓库顺序、CI 发版 workflow、OpenClaw Bridge 脚本。
- Android Manifest、外部分享 Intent、FileProvider、更新检查、Release 签名链路。
- OpenAI Compatible、OpenAI Responses、Gemini、OpenClaw、MCP Streamable HTTP、SSE 解析。
- Room、HistoryManager、状态控制器、协程生命周期、缓存管理。
- Compose UI、图片编辑、附件、WebView、数学渲染、抽屉、设置导入导出。

## 官方来源核对

- Gradle current：`9.5.1`，`gradle-9.5.1-all.zip.sha256=c72fb9991f6025cbe337d52ba77e531b3faf62bdd3e348fe1ccee9f51c71adb0`，来源 `https://services.gradle.org/versions/current` 与 `https://services.gradle.org/distributions/gradle-9.5.1-all.zip.sha256`。
- AGP：Google Maven 最新元数据为 `9.3.0-alpha11`，稳定版尾部为 `9.2.1`；当前项目保持 `9.2.1`，来源 `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml`。
- Compose BOM：`2026.05.01`，来源 `https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml`。
- Ktor：`3.5.0`，来源 `https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml`。
- OkHttp：`5.4.0`，来源 `https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml`。
- Room：`2.8.4`，来源 `https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml`。
- KSP：已升级到官方最新 `2.3.9`；KSP 2.3.9 release 标为 Latest，2.3.6 起改进 built-in Kotlin 检测，来源 `https://github.com/google/ksp/releases`。
- Android built-in Kotlin：AGP 9.0 起默认启用；迁移需移除 `org.jetbrains.kotlin.android`、删除 `android.builtInKotlin=false`，并使用 `android.sourceSets` 或 Variant API 处理 Kotlin sourceSets，来源 `https://developer.android.com/build/migrate-to-built-in-kotlin`。
- npm `ws`：脚本已更新到 `^8.21.0`。
- MCP Streamable HTTP：GET SSE 应使用 `Accept: text/event-stream`，来源 `https://modelcontextprotocol.io/specification/2025-06-18/basic/transports`。
- OpenAI Responses：`input_image.image_url` 是字符串字段，来源 `https://platform.openai.com/docs/api-reference/responses/create`。
- Android WebView：移除 WebView 后需 `destroy()`；file URL 跨源访问应显式关闭，来源 `https://developer.android.com/reference/android/webkit/WebView` 与 `https://developer.android.com/reference/android/webkit/WebSettings`。

## 已修复问题

### P1 外部分享入口不可达

- 位置：`app/src/main/AndroidManifest.xml`、`statecontroller/MainActivity.kt`
- 现象：`MainActivity exported=false` 却直接挂 `ACTION_SEND` intent-filter，外部应用分享文本时入口可能不可见。
- 修复：新增 exported `ShareReceiver` activity-alias 承接 `text/plain` 与 `text/*`，保留 `MainActivity` 非导出。

### P2 `text/plain + EXTRA_STREAM` 文本分享被吞

- 位置：`statecontroller/MainActivity.kt`
- 现象：文本文件分享常携带 `EXTRA_STREAM`，旧逻辑先按 `EXTRA_TEXT` 处理。
- 修复：优先处理 `EXTRA_STREAM`，并在 IO 线程读取文件内容。

### P2 WebView 资源释放和 file 访问边界过宽

- 位置：`ui/components/web/WebPreviewDialog.kt`、`ui/components/math/MathRenderer.kt`
- 现象：预览 WebView 缺 `destroy()`；本地模板需要读 assets，但旧设置同时允许 `content://` 和 file URL 跨源访问。
- 修复：预览退出时 `stopLoading()`、`about:blank`、`removeAllViews()`、`destroy()`；保留 `allowFileAccess=true`，关闭 `allowContentAccess`、`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs`。

### P1 MCP Streamable HTTP GET SSE 请求头不符合规范

- 位置：`data/mcp/transport/StreamableHttpClientTransport.kt`
- 现象：GET SSE 分支发送 `Accept: application/json`。
- 修复：改为 `Accept: text/event-stream`。

### P1 OpenAI Responses 图片输入 schema 错误

- 位置：`data/network/OpenAIResponsesClient.kt`
- 现象：`input_image.image_url` 被写成 `{ "url": ... }` 对象。
- 修复：改成字符串形式，并新增 payload 回归测试。

### P2 会话分享 I/O 与 URI 授权兼容弱

- 位置：`util/share/ConversationExporter.kt`、`statecontroller/AppViewModel.kt`
- 现象：长会话 Markdown 生成和文件写入可能占用主线程；分享 Intent 缺 `ClipData`。
- 修复：生成和写文件切到 `Dispatchers.IO`，分享启动回主线程，并增加 `ClipData` 授权。

### P1 默认版本号与 release-please 基线不一致

- 位置：`app/build.gradle.kts`、`.release-please-manifest.json`
- 现象：Gradle 默认 `baseVersionName=1.7.5`，release-please 已到 `1.20.0`。
- 修复：同步默认版本为 `1.20.0`。

### P2 更新弹窗双 `v` 与 major 强更漏判

- 位置：`data/DataClass/VersionInfo.kt`、`ui/screens/settings/dialogs/UpdateDialog.kt`
- 现象：`v1.x.x` 可能显示成 `vv1.x.x`；`1.2.0 -> 2.2.0` 未触发 major 强更。
- 修复：显示前去 `v`；强更判断计入 major 差值，并新增版本边界测试。

### P2 Gradle Wrapper 与 OpenClaw Bridge 依赖落后

- 位置：`gradle/wrapper/gradle-wrapper.properties`、`scripts/install-openclaw-bridge.sh`
- 现象：Wrapper 不是当前版且缺校验和；Bridge 脚本 `ws` 版本落后。
- 修复：Wrapper 更新到 `9.5.1-all` 并加入 SHA；`ws` 更新到 `^8.21.0`。

### P3 Gradle local.properties 读取流未关闭

- 位置：`app/build.gradle.kts`
- 现象：`FileInputStream(localPropertiesFile)` 未 `use`。
- 修复：改为 `FileInputStream(...).use { properties.load(it) }`。

### P1 CI PR 构建错误依赖 release 签名

- 位置：`.github/workflows/build-artifacts.yml`
- 现象：PR 跑 release 构建时可能因无 keystore 失败。
- 修复：PR/非 tag workflow 跑 debug，tag/release 才走 release 签名链路。

### P2 Room schema 与 5->6 迁移审计能力不足

- 位置：`data/database/AppDatabase.kt`、`app/build.gradle.kts`、`app/schemas/.../5.json`、`app/schemas/.../6.json`、`app/src/test/java/com/android/everytalk/data/database/AppDatabaseMigrationTest.kt`
- 现象：旧配置未导出 schema，且 `MIGRATION_5_6` 没有旧库迁移回归测试。
- 修复：`exportSchema=true`，新增 `room.schemaLocation`，生成 `5.json` 与 `6.json`，新增 5->6 迁移测试，验证 OpenClaw 字段数据经过空迁移后保持不变。

### P2 CacheManager 生命周期问题（后续已移除）

- 位置：`util/cache/CacheManager.kt`
- 现象：`cleanup()` 取消单例 scope 后，后续 warmup 和 TTL 任务无法再启动。
- 当时修复：scope 改为可恢复，并补 `CacheManagerTest`。
- 后续处理：确认该组件没有生产消费者后，已删除 `CacheManager.kt` 与对应测试。

### P1 图片编辑大图 OOM 风险

- 位置：`ui/screens/ImageGeneration/ImageGenerationMessagesList.kt`
- 现象：编辑预览链路存在全尺寸解码和复制。
- 修复：预览和编辑入口统一采样解码，最大边限制为 `2048`。

### P1 直连内联附件内存放大

- 位置：`data/network/ApiClient.kt`、`util/storage/FileManager.kt`
- 现象：内联附件会 `readBytes()` 后 Base64，同一文件多份驻留。
- 修复：直连内联附件统一 10MB 上限，超限发 error/finish。

### P2 设置导入整文件读入

- 位置：`ui/screens/settings/SettingsScreen.kt`
- 现象：长度未知时直接 `readText()`，大 JSON 会放大内存峰值。
- 修复：改为流式读取，超过 50MB 直接拒绝。

### P2 抽屉分组查找重复遍历

- 位置：`ui/screens/MainScreen/AppDrawerContent.kt`
- 现象：每个会话 item 都遍历分组集合查所属组。
- 修复：先构造 `groupByConversationId` 反查表。

### P1 HistoryManager 保存队列丢强制保存

- 位置：`ui/screens/viewmodel/HistoryManager.kt`
- 现象：`Channel.CONFLATED` 会覆盖连续 force save；文本和图像共享防抖 job，会互相取消。
- 修复：改 `Channel.BUFFERED`；文本/图像防抖 job 拆分；队列消费沿用注入 scope，测试可控。

### P2 图像 Loading 计时不刷新

- 位置：`statecontroller/facade/MessageItemsController.kt`
- 现象：图像生成等待首个内容时，loading 文案运行时间不推进。
- 修复：图像生成 flow 组合 `tickerFlow`，缓存命中校验加入 loading 文案。

### P2 版本目录和仓库顺序

- 位置：`gradle/libs.versions.toml`、`app/build.gradle.kts`、`settings.gradle.kts`
- 现象：Ktor、OkHttp、Compose BOM、Room testing 部分硬编码且版本落后；阿里镜像优先导致 OkHttp 5.4.0 jar 解析失败。
- 修复：关键依赖统一走 catalog 并升级；`google()`、`mavenCentral()` 放到镜像前。

### P3 图片画笔编辑状态不一致

- 位置：`ui/screens/ImageGeneration/ImageGenerationMessagesList.kt`
- 现象：预览笔迹是蓝色，最终合成是红色；取消只关闭 Dialog，不释放 `brushBaseBitmap`。
- 修复：合成颜色使用同一 `strokeColor`；取消、返回、完成后清空 bitmap 引用。

### P2 图片保存和分享整图无界读入

- 位置：`ui/screens/ImageGeneration/ImageGenerationMessagesList.kt`、`util/storage/FileManager.kt`、`util/storage/LimitedByteReader.kt`
- 现象：保存、分享、URI 转换、HTTP 图片缓存路径曾直接 `readBytes()` 或 `body.bytes()`，超大图片可能造成内存峰值失控。
- 修复：新增 `readAtMost` 字节读取工具，图片原始字节读取统一限制为 50MB，FileManager 灵活来源读取沿用既有 10MB 上限，并补充超限回归测试。

### P2 Release lint gate 关闭

- 位置：`app/build.gradle.kts`
- 现象：`lint { checkReleaseBuilds = false }` 导致 release 构建不执行 release lint 门禁。
- 修复：改为 `checkReleaseBuilds = true`，并通过 `lintVitalRelease` 验证 fatal release lint 门禁可运行。

### P2 AGP 10 兼容风险

- 位置：`gradle.properties`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle/libs.versions.toml`
- 现象：存在 `android.builtInKotlin=false`、`android.newDsl=false`，且构建由 `kotlin-android` 插件触发 `applicationVariants/testVariants/unitTestVariants` 旧 API 警告。
- 修复：迁移到 AGP built-in Kotlin，移除 `kotlin-android` 插件声明与应用，删除两个 opt-out 开关；KSP 升级到 `2.3.9` 后兼容 built-in Kotlin 生成源目录注入。

### P3 跨模式导航依赖固定延迟

- 位置：`statecontroller/MainActivity.kt`
- 现象：文本/图像模式切换后固定 `delay(400)` 再加载历史会话，快速连续点击或动画耗时变化时可能错时加载。
- 修复：改为等待 `NavBackStackEntry` 进入目标 route 后再加载历史，保留 1 秒兜底，避免导航异常时操作挂起。

### P2 单元测试后台 flow scope 泄漏

- 位置：`app/src/test/java/com/android/everytalk/statecontroller/facade/MessageItemsControllerTestAccess.kt`、`MessageItemsControllerStatusTest.kt`
- 现象：全量 `testDebugUnitTest` 中，`MessageItemsControllerStatusTest` 通过后仍有后台 flow 调用 `android.util.Log.d`，`tearDown` 已释放 mock，异常泄漏到后续 `HistoryManagerCustomPromptPersistenceTest`。
- 修复：测试 controller 保存并关闭自建 `CoroutineScope`，`tearDown` 先关闭全部测试 controller，再释放 MockK 静态 mock。

### P2 Ktor 3.5 API 兼容警告

- 位置：`data/mcp/transport/StreamableHttpClientTransport.kt`、`data/network/*DirectClient.kt`、`data/network/direct/*DirectClient.kt`
- 现象：流读取仍使用旧 `readUTF8Line()`，二进制响应仍使用旧 `readBytes()`，升级到 Ktor 3.5 后会持续输出弃用警告。
- 修复：SSE 行读取统一改为 `readLine()`；图片、TTS 等二进制响应读取改为 `readRawBytes()`。

### P2 MCP/Flow 实验 API 未显式声明

- 位置：`data/mcp/McpClientManager.kt`、`statecontroller/ApiHandler.kt`
- 现象：`McpJson` 和 `streamChatResponse` 使用实验 API，但声明不集中，后续 Kotlin/serialization 升级时容易变成编译错误。
- 修复：分别补 `@OptIn(ExperimentalSerializationApi::class)` 与 `@OptIn(FlowPreview::class)`，把实验 API 边界固定在实际使用点。

### P1 Aliyun STT 取消流程使用 GlobalScope

- 位置：`data/network/direct/AliyunRealtimeSttClient.kt`
- 现象：取消会话时通过 `GlobalScope.launch` 异步关闭 WebSocket，调用方生命周期无法管理，存在后台任务泄漏风险。
- 修复：改为类内 `closeScope`，关闭结束后执行 `cleanup()`；无 session 时直接清理。

### P3 STT WebSocket URL 死条件

- 位置：`data/network/direct/SttDirectClient.kt`
- 现象：`apiUrl` 在当前分支已经是非空字符串，`apiUrl != null` 判断恒真，增加误读成本。
- 修复：删除恒真判空，仅保留 `ws://`、`wss://` 分支判断。

### P2 无界字节读取残留

- 位置：`statecontroller/AppViewModel.kt`、`ui/screens/viewmodel/DataPersistenceManager.kt`、`util/audio/AudioRecorderHelper.kt`、`data/network/VoiceChatSession.kt`
- 现象：URI 转 Base64、远程图片归档、录音文件编码仍存在无上限 `readBytes()`；语音直连还会先写临时 WAV 再整文件读回，造成重复 I/O 和内存峰值。
- 修复：URI/录音 Base64 读取限制为 10MB；远程图片归档限制为 50MB 且确保 `HttpURLConnection.disconnect()`；直连 WAV 改为内存中拼接 WAV header 和 PCM，移除临时文件读回。

### P2 语法高亮器 Java Regex 可空捕获组

- 位置：`ui/components/syntax/languages/*Highlighter.kt`、`RegexMatchUtils.kt`
- 现象：Kotlin 2.3 对 `Matcher.group()` 可空性收紧后，高亮器大量把 `String?` 传给非空 `Token.text`，潜在空捕获会导致高亮异常。
- 修复：新增包内 `groupText()` 小工具，所有高亮器捕获组文本统一转为非空字符串，保留可选捕获组的判空逻辑。

### P3 Compose/Android 弃用 API 收敛

- 位置：`ui/components/content/*`、`ui/components/web/WebPreviewDialog.kt`、`ui/screens/*`、`ui/components/markdown/*`、`ui/components/modifier/ShadowModifier.kt`、`ui/components/table/TableAwareText.kt`、`app/src/test/java/**/*Test.kt`
- 现象：仍使用 `LocalClipboardManager`、`ClickableText`、旧 `menuAnchor()`、旧 keyframe `with`、旧绘图/图标/分割线 API；编译 warning 淹没有效信号。
- 修复：复制入口迁移到 `LocalClipboard.setClipEntry`；About 链接迁移到 `LinkAnnotation.Url`；下拉锚点改 `PrimaryNotEditable`；绘图/分割线/图标/动画/平台 Paint API 改为新接口；必须保留的 WebView file URL 安全开关和 dialog 透明系统栏用局部 helper 封装；Compose 测试 rule import 迁移到 v2。

### P2 Gradle Metaspace 配置偏小

- 位置：`C:\Users\33039\.gradle\gradle.properties`
- 现象：KSP 重新生成后曾提示 Gradle Daemon Metaspace 接近上限。
- 修复：全局 `org.gradle.jvmargs` 从 `-Xmx2048m -XX:MaxMetaspaceSize=512m` 调整为 `-Xmx4096m -XX:MaxMetaspaceSize=1024m`，后续强制编译未复现该告警。

## 仍存在风险

- 本轮明确记录的未修复风险已清空；构建警告仅剩 Room KSP 生成代码恒 false，见下方。

## 验证记录

- `:app:testDebugUnitTest --tests "com.android.everytalk.ui.screens.viewmodel.HistoryManagerCustomPromptPersistenceTest.queued force saves persist text and image conversations independently"`：通过。
- `:app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.facade.MessageItemsControllerStatusTest.image loading indicator runtime text advances while waiting for first content"`：通过。
- `CacheManagerTest` 在组件移除前通过；同批次其余专项测试均通过。
- `:app:testDebugUnitTest --tests "com.android.everytalk.data.database.AppDatabaseMigrationTest.migration 5 to 6 preserves api configs with openclaw columns" --stacktrace`：通过。
- `:app:testDebugUnitTest --tests "com.android.everytalk.data.database.AppDatabaseMigrationTest.migration 5 to 6 preserves api configs with openclaw columns" --tests "com.android.everytalk.data.database.RoomDataSourceConversationParamsTest" --stacktrace`：通过。
- `:app:testDebugUnitTest --tests "com.android.everytalk.util.storage.LimitedByteReaderTest" --stacktrace`：通过。
- `:app:lintVitalRelease --stacktrace`：通过。
- `:app:compileDebugKotlin --stacktrace -Pandroid.debug.obsoleteApi=true`：通过；迁移前确认旧 Variant API 调用方为 `kotlin-android` 插件，迁移后无旧 Variant API 警告。
- `:app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.facade.MessageItemsControllerStatusTest" --tests "com.android.everytalk.ui.screens.viewmodel.HistoryManagerCustomPromptPersistenceTest" --stacktrace`：通过。
- `:app:testDebugUnitTest --stacktrace`：通过。
- `:app:compileDebugKotlin --stacktrace`：通过。
- `:app:assembleDebug --stacktrace`：通过。
- `git diff --check`：退出码 0；仅有 LF 将被 CRLF 替换的换行提示。
- `:app:kspDebugKotlin :app:compileDebugKotlin --rerun-tasks --stacktrace --warning-mode all`：通过；主源码 warning 已清空，仅剩 Room KSP 生成的 `ChatDao_Impl.kt` 两条条件恒 false。
- `:app:testDebugUnitTest --stacktrace --warning-mode all`：通过；Compose 测试 rule 弃用 warning 已清空。
- `:app:assembleDebug --stacktrace --warning-mode all`：通过。
- `:app:lintVitalRelease --stacktrace --warning-mode all`：通过；release 编译仅剩 Room KSP 生成的 `ChatDao_Impl.kt` 两条条件恒 false。
- `:app:compileDebugKotlin --stacktrace --warning-mode all`：通过；debug 编译仅剩 Room KSP 生成的 `ChatDao_Impl.kt` 两条条件恒 false。
- `git diff --check`：退出码 0；仅有 LF 将被 CRLF 替换的换行提示。

## 构建警告保留

- Kotlin 强制重编译仅保留 Room KSP 生成文件 `ChatDao_Impl.kt` 两条条件恒 false warning，主源码 warning 已清空。
- KSP 重新生成后曾提示 Gradle Daemon Metaspace 接近上限；全局 JVM 参数上调后强制重编译未复现。
