# Issue #104 文本模式内联生成图片修复方案

| 项目 | 内容 |
|---|---|
| Issue | [#104 生成图像无法正常放大预览切导致应用崩溃](https://github.com/roseforljh/EveryTalk/issues/104) |
| 文档状态 | 已完成代码链路核查和工程自审，等待人工审查，尚未实施业务代码 |
| 制定日期 | 2026-07-20 |
| 目标分支 | `like-gpt`，基线提交 `1baebda8`，同时考虑当前未提交的 Markdown 渲染和历史加载并发改动 |
| Android 工程 | `app1/` |
| 问题主路径 | 文本模式中的 Gemini 代码执行图片和其他流式图片事件 |
| 评审结论 | 修复方案通过架构、代码质量、测试、性能四项自审，无未解决决策 |

## 1. 文档目的

本文档给出 Issue #104 的完整修复计划，覆盖问题事实、证据等级、根因、目标数据流、职责边界、实施顺序、文件级改动、历史兼容、异常处理、安全限制、性能预算、测试矩阵、真机验收、回滚和自审结果。

本轮只制定和审查方案，不修改业务代码，不执行 Git 提交。

## 2. 最终结论

当前最应解决的问题是消息数据边界失守。模型返回的图片二进制数据被编码成 `data:image/...;base64,...`，随后作为普通 Markdown 文本写入 `Message.text`。这同时造成三类故障：

1. 图片元数据缺失。生成图没有进入 `Message.imageUrls`，预览器找不到被点击图片。
2. Android IPC 载荷失控。复制和分享把完整 Base64 正文交给剪贴板服务或 `Intent`，存在超过 Binder 事务缓冲区的高风险。
3. 持久化和恢复成本失控。巨大的 Base64 字符串进入流式状态、Markdown 解析、Room 保存和启动加载链路。

修复原则固定如下：

- 文本模式允许模型输出图片，不禁止 Gemini 代码执行或多模态能力。
- 图片事件到达后必须先完成安全校验和本地落盘。
- `Message.text` 只保存说明文字和指向本地文件或短远程地址的 Markdown 引用。
- `Message.imageUrls` 保存与正文图片一致、可预览的地址列表。
- `data:image` 不得进入新消息的 `text`、`parts`、`imageUrls`、剪贴板或分享 `Intent`。
- 图片落盘失败时，Base64 不得回退写入消息；界面显示短错误说明。
- 点击地址无法在预览集合中匹配时，只打开被点击图片，禁止回退到索引 `0`。
- 旧消息按会话和消息粒度迁移，成功后回写，失败时保留数据库原记录并启用外部传输安全阀。

## 3. 审查结论校正

### 3.1 已证实事实

| 编号 | 结论 | 证据 | 可信度 |
|---|---|---|---|
| F1 | 录屏发生在文本模式 | 录屏消息底部存在文本模式的复制、分享、更多操作栏；图像模式消息列表使用另一套 Footer | 高 |
| F2 | 文本模式能够收到模型图片 | [`GeminiDirectClient.kt`](../app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt) 将 Gemini `inlineData` 转成图片数据 URI，并发送 `CodeExecutionResult` | 高 |
| F3 | Base64 图片进入正文 | [`MessageProcessor.kt`](../app1/app/src/main/java/com/android/everytalk/util/messageprocessor/MessageProcessor.kt) 和 [`ApiHandler.kt`](../app1/app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt) 都会构造图片 Markdown | 高 |
| F4 | 生成图没有同步进入文本消息的 `imageUrls` | `ApiHandler.processStreamEvent()` 的文本流图片分支只追加正文，没有更新 `imageUrls` | 高 |
| F5 | 点击生成图会错误回退到第一张候选图 | [`ChatScreen.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/ChatScreen.kt) 对 `indexOf()` 的 `-1` 使用 `coerceAtLeast(0)` | 高 |
| F6 | 复制直接传递完整正文 | [`ClipboardController.kt`](../app1/app/src/main/java/com/android/everytalk/statecontroller/controller/media/ClipboardController.kt) 直接把传入文本写入 `ClipData` | 高 |
| F7 | 分享直接传递完整正文 | [`ChatMessagesList.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt) 直接把 `latestMessage.text` 写入 `Intent.EXTRA_TEXT` | 高 |
| F8 | Android Binder 事务缓冲区目前为 1MB，并由进程中的事务共享 | Android 官方 [`TransactionTooLargeException`](https://developer.android.com/reference/android/os/TransactionTooLargeException) 文档 | 高 |
| F9 | 文本模式保存链路没有执行现有图片落盘转换 | `DataPersistenceManager.saveChatHistory()` 和 `saveLastOpenChat()` 只在图像模式调用 `persistInlineAndRemoteImages()` | 高 |
| F10 | Room 会话保存具有事务保护 | [`ChatDao.kt`](../app1/app/src/main/java/com/android/everytalk/data/database/daos/ChatDao.kt) 的 `saveSessionWithMessages()` 标记了 `@Transaction` | 高 |
| F11 | 当前 `like-gpt` 渲染链路没有把 AI 图片点击回调传入 MikePenz 图片组件 | [`UnifiedMarkdownRenderer`](../app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt) 和 [`MikePenzMarkdownRenderer`](../app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt) 没有图片点击参数 | 高 |

### 3.2 高可信推断

| 编号 | 推断 | 依据 | 限制 |
|---|---|---|---|
| I1 | 分享和复制崩溃很可能由 `TransactionTooLargeException` 引起 | Base64 约增加三分之一体积，正文又被传入 Binder；官方缓冲区为 1MB | Issue 未提供 logcat，实施验收时必须用日志确认 |
| I2 | 应用崩溃后“所有会话消失”可能包含未及时保存和启动加载失败两种表现 | 崩溃会中断最后一次保存；`loadInitialData()` 的外层异常处理会清空多个内存状态 | 没有数据库文件和崩溃日志，不能确认具体占比 |
| I3 | 当前分支点击故障可能被“图片无法点击”暂时掩盖 | 新 Markdown 入口没有传播 `onImageClick`，旧索引回退逻辑仍保留 | 恢复点击后必须同步修正预览选择逻辑 |

### 3.3 明确撤回的过强结论

- 不宣称“图像模式绝对没有问题”。当前没有证据表明图像模式走录屏中的同一故障链路，仍需真机回归。
- 不宣称“数据库删除了所有历史”。Room 保存使用事务，现有证据只支持大文本持久化、未保存状态和加载异常风险。
- 不把 Binder 异常写成已确认事实。没有 logcat 时只能标记为高可信推断。
- 不通过禁用文本模式图片输出来规避问题。Gemini 官方文档明确支持代码执行结果包含图片内容，应用需要正确承载这种输出。

## 4. 为什么文本模式能够生成图片

EveryTalk 的“文本模式”当前表示聊天页面和会话路由，不表示响应 MIME 只能是文本。文本模式仍会调用具备代码执行能力的 Gemini 模型。

Google 官方 [Gemini code execution](https://ai.google.dev/gemini-api/docs/code-execution) 文档说明，代码执行结果可以包含图片内容。当前客户端收到 `inlineData` 后执行以下转换：

```text
Gemini inlineData
        |
        v
data:image/<mime>;base64,<payload>
        |
        v
AppStreamEvent.CodeExecutionResult.imageUrl
```

因此，文本模式生成图片属于模型能力和当前协议处理共同产生的正常结果。故障位于应用对图片结果的存储、状态和交互处理。

## 5. 当前故障数据流

```text
文本模式请求
    |
    v
Gemini 代码执行或图片内联结果
    |
    v
GeminiDirectClient
构造 data:image/...;base64,...
    |
    v
AppStreamEvent.CodeExecutionResult(imageUrl)
    |
    +-------------------------------+
    |                               |
    v                               v
MessageProcessor                ApiHandler
向内部文本缓冲追加             向 StreamingBuffer 追加
Base64 Markdown                Base64 Markdown
    |                               |
    +---------------+---------------+
                    |
                    v
        Message.text / parts 包含大 Base64
        Message.imageUrls 仍为空
                    |
        +-----------+-----------+
        |           |           |
        v           v           v
   Markdown 渲染   点击预览     复制或分享
        |           |           |
        |       indexOf = -1     完整正文进入 Binder
        |           |           |
        |       强制变为 0       可能超过 1MB
        |           |           |
        v           v           v
   能看到生成图   打开参考图    应用崩溃
```

附加风险：

- 文本保存链路把大 Base64 原样写入 Room。
- Markdown 层需要解析和持有大字符串。
- 错误日志可能意外输出完整数据 URI。
- 取消和异常分支可能吞掉 `CancellationException`。
- 当前图片落盘实现分散在 `ApiHandler`、`DataPersistenceManager` 和 `FileManager`，失败回退规则不一致。

## 6. 修复目标和非目标

### 6.1 修复目标

1. 文本模式生成图片能够显示、点击、放大、滑动和重启恢复。
2. 图片点击始终打开被点击图片。
3. 新生成图片的 Base64 不进入消息正文、结构化 parts、图片地址列表和 Room。
4. 复制、分享、导出文本不会携带 Base64 图片。
5. 复制和分享载荷具有明确上限，不触发超大 Binder 事务。
6. 文本模式和图像模式共用一套图片归档规则。
7. 旧文本会话中的 Base64 图片能够幂等迁移。
8. 一条会话加载失败时，不清空已经成功加载的配置和其他模式状态。
9. 所有新增协程路径正确传播取消。
10. 日志只记录类型、字符数、字节数、消息 ID 和结果，不记录图片内容。

### 6.2 非目标

- 不限制模型在文本模式调用代码执行或返回图片。
- 不修改模型选择、Provider 路由或工具开关。
- 不新增 Room 字段，不升级数据库版本。
- 不重做图片预览 UI。
- 不改变图像模式的布局和图像生成参数。
- 不新增第三方依赖。
- 不恢复旧 Markdown 渲染器。
- 不修改图片下载到系统相册的既有功能。
- 不在本修复中重构整个历史存储体系。

## 7. 目标架构

### 7.1 目标数据流

```text
Provider 图片事件
    |
    v
ApiHandler 图片事件入口
    |
    v
校验来源类型、MIME 和大小
    |
    v
DataPersistenceManager
    |
    v
FileManager 统一归档
    |
    +-------------------------+
    |                         |
    v                         v
成功                         失败
本地绝对路径或短 URL          丢弃危险 data URI
    |                         输出短错误说明
    v
在同一主线程状态段更新同一条 Message
    |
    +-------------------------+
    |                         |
    v                         v
text 追加短 Markdown          imageUrls 追加同一地址
    |                         |
    +------------+------------+
                 |
                 v
          流式显示与 Room 保存
                 |
        +--------+---------+
        |                  |
        v                  v
   图片点击回调         外部传输净化
        |                  |
        v                  v
   精确预览选择      文本无图片且不超过 256KiB
```

### 7.2 职责划分

| 层 | 唯一职责 |
|---|---|
| Provider Client | 将服务商响应转换成 `AppStreamEvent`，不决定消息持久化格式 |
| `ApiHandler` | 成为流式图片事件的唯一消息状态负责人，协调归档、正文和 `imageUrls` 更新 |
| `MessageProcessor` | 处理文本、推理和代码结果文本，不再把图片数据写入内部文本缓冲 |
| `DataPersistenceManager` | 提供消息图片归档入口，执行旧消息迁移和回写 |
| `FileManager` | 负责来源读取、大小限制、MIME 校验和文件写入 |
| Markdown 渲染层 | 渲染短图片地址并传递点击事件，不处理 Base64 持久化 |
| 预览选择层 | 归一化地址，生成候选集合和初始索引 |
| 外部传输净化层 | 移除 Markdown 图片和裸数据 URI，限制 UTF-8 字节数 |

### 7.3 数据不变量

新消息必须满足以下不变量：

```text
Message.text 不包含 data:image
Message.parts 不包含图片 Base64
Message.imageUrls 不包含 data:image
Markdown 图片地址与 imageUrls 中的地址能够归一化匹配
imageUrls 保持事件顺序并去重
剪贴板和分享文本不包含 data:image
剪贴板和分享文本 UTF-8 长度小于等于 256KiB
```

旧消息迁移失败时允许数据库原记录暂时保留 Base64，但必须满足：

- 外部传输安全阀始终生效。
- 失败消息不会导致整个历史列表被清空。
- 失败原因有脱敏日志。
- 下次启动可以再次尝试迁移。

## 8. 行为定义

### 8.1 文本模式新生成图片

1. 收到 `CodeExecutionResult.imageUrl` 或 `ImageGeneration.imageUrl`。
2. 仅接受 `data:image`、`http://`、`https://` 三类实时来源。
3. `data:image` 必须先落盘，成功后才进入消息状态。
4. HTTP 图片归档最多等待 15 秒；超时或失败时保留短远程 URL，随后继续处理流事件。
5. 图片 Markdown 使用短地址，例如：

   ```markdown
   ![Generated Image](/data/user/0/com.android.everytalk/files/chat_attachments/img_xxx.png)
   ```

6. 同一地址同时写入 `imageUrls`。
7. 相同事件重复到达时不重复追加正文和元数据。
8. 归档失败时追加短说明：`> 图片生成成功，但本地保存失败。`
9. 失败分支不保留 Base64，不输出完整来源日志。

### 8.2 点击预览

预览选择返回以下结构：

```text
clickedSource
candidates
initialIndex
```

规则：

1. 候选图片从当前 `messages` 获取，包含用户图片附件和 AI `imageUrls`。
2. 候选地址按首次出现顺序去重。
3. 比较前统一处理绝对路径、`file://`、`content://`、HTTP(S) 和 data URI。
4. 找到匹配时打开完整候选集合，并使用真实索引。
5. 找不到匹配时使用 `listOf(clickedSource)` 和索引 `0`。
6. 禁止把未匹配状态映射到其他候选图的索引 `0`。
7. `AppViewModel.showImageViewer(urls, index)` 增加边界保护，空列表直接返回，索引使用 `coerceIn()`。

### 8.3 复制

1. 所有复制入口继续统一调用 `AppViewModel.copyToClipboard()`。
2. `ClipboardController` 使用既有协程作用域在 `Dispatchers.Default` 净化文本，再回到主线程调用系统服务。
3. 移除全部 Markdown 图片节点。
4. 移除裸 `data:image/...` 载荷。
5. 普通链接、代码块、公式源码和文字保持不变。
6. 净化后超过 256KiB UTF-8 时，在完整字符边界截断并追加 `\n\n[内容过长，已截断]`。
7. 系统剪贴板调用失败时捕获运行时异常并显示“复制失败”。
8. 净化后为空时复制“[图片内容已省略]”，避免产生无反馈的空剪贴板。

### 8.4 分享

1. 文本分享与复制使用同一个净化函数和相同字节上限。
2. Footer 使用 `rememberCoroutineScope()`，在 `Dispatchers.Default` 净化文本，再回到主线程构造和启动分享 Intent。
3. `Intent.EXTRA_TEXT` 只接收净化结果。
4. `startActivity()` 用 `runCatching` 包裹，失败时通过现有 Snackbar 显示“分享失败”。
5. 分享行为仍为 `text/plain`。
6. 本修复不把生成图作为二进制附件加入文本分享。

### 8.5 导出文本

导出文本同样移除 Base64 图片，避免生成超大 Markdown 文件。导出写文件不受 Binder 256KiB 上限约束，因此只执行图片净化，不截断正文。

### 8.6 图像模式

图像模式继续以 `imageUrls` 为图片主数据。统一归档方法应用于图像模式，禁止归档失败后把危险数据 URI 回填到消息。图像模式复制文本自动获得外部传输安全阀。

## 9. 实施阶段

### 阶段 0：固定故障基线

目标：先用测试证明当前故障，防止实施过程中误判。

任务：

- [ ] 增加测试，证明 `CodeExecutionResult` 图片当前会进入处理器文本。
- [ ] 增加测试，证明生成图地址不在候选集合时旧逻辑会选择第一张图。
- [ ] 增加测试，证明外部传输净化前仍含数据 URI。
- [ ] 保存 Issue 录屏中的最小复现步骤。
- [ ] 记录当前相关测试和 Debug 构建结果。

验证：

```powershell
./gradlew :app:testDebugUnitTest --tests "*MessageProcessorFinalizePartsTest*" --tests "*ChatScreenImagePreviewSelectionTest*"
```

### 阶段 1：加入外部传输安全阀

优先级：P0。

目标：即使历史消息仍含 Base64，复制和分享也不能把大载荷发送给 Android 系统服务。

任务：

- [ ] 新增线性扫描器，识别 Markdown 图片节点和裸数据 URI。
- [ ] 新增 `prepareTextForExternalTransfer()`。
- [ ] 按 UTF-8 字节限制安全截断，禁止切断代理对和多字节字符。
- [ ] `ClipboardController.copyToClipboard()` 调用净化函数。
- [ ] `ClipboardController.exportMessageText()` 调用只去图片、不截断的净化函数。
- [ ] `AiMessageFooterItem` 分享入口调用净化函数。
- [ ] 大文本扫描在 `Dispatchers.Default` 执行，Android 剪贴板和 `startActivity()` 调用回到主线程。
- [ ] 净化协程取消时不显示失败提示，并继续传播 `CancellationException`。
- [ ] 复制和分享失败时向用户显示短错误。

边界：

- 扫描器必须为 O(n)。
- 禁止使用可能对超长 Base64 产生灾难性回溯的正则表达式。
- 扫描器遇到未闭合 Markdown 图片时，继续检测裸数据 URI。
- 不修改普通 Markdown 链接。

### 阶段 2：统一图片归档入口

优先级：P0。

目标：文本模式和图像模式共用同一套图片来源校验和落盘规则。

任务：

- [ ] 在 `FileManager` 中为 `loadBytesFromFlexibleSource()` 增加调用方可传入的最大字节数。
- [ ] 在 Base64 解码前根据载荷长度估算解码后大小。
- [ ] 实时生成图片的解码后上限固定为 16MiB。
- [ ] 仅允许图片 MIME，校验响应 `Content-Type` 和基础文件签名。
- [ ] 实时 `data:image` 必须包含 `;base64,`；旧历史中的其他 data URI 格式迁移失败时保留内存和数据库原消息。
- [ ] 通用 `loadBytesFromFlexibleSource()` 保持非图片调用能力，图片格式限制只放在消息图片归档入口。
- [ ] 文件写入使用临时文件，成功关闭后再原子重命名。
- [ ] 日志只记录来源 scheme、MIME、长度和消息 ID。
- [ ] 捕获异常时显式重新抛出 `CancellationException`。
- [ ] 在 `DataPersistenceManager` 新增 `persistMessageImageSource()`，内部委托 `FileManager`。
- [ ] 在 `HistoryManager` 新增窄接口，供 `ApiHandler` 调用持久化能力。
- [ ] 再次确认 `persistImageImmediate()` 没有调用方，然后删除该重复实现。
- [ ] `persistInlineAndRemoteImages()` 改为调用统一入口。

实时来源策略：

| 来源 | 行为 |
|---|---|
| `data:image` | 校验、落盘、返回本地绝对路径；失败后返回危险来源失败状态 |
| `https://` | 在 15 秒总超时内下载并落盘；失败时保留短远程 URL |
| `http://` | 本修复保留现有兼容行为并记录 scheme，不新增额外放宽规则 |
| 绝对路径和 `file://` | 仅用于已持久化消息，验证文件存在后原样返回 |
| `content://` | 仅用于本地历史和附件，不接受为实时 Provider 图片来源 |
| 其他 scheme | 拒绝 |

### 阶段 3：修复流式图片消息状态

优先级：P0。

目标：`ApiHandler` 成为图片事件的唯一消息状态负责人。

任务：

- [ ] `ApiHandler.processStreamEvent()` 在进入主线程状态更新前归档图片。
- [ ] 同时处理 `CodeExecutionResult.imageUrl` 和 `ImageGeneration.imageUrl`。
- [ ] 图片归档在 `Dispatchers.IO` 执行。
- [ ] 为每条消息维护稳定的图片事件顺序。
- [ ] 一次消息状态更新同时追加短 Markdown 和 `imageUrls`。
- [ ] 对地址进行归一化去重，重复事件不重复追加。
- [ ] 图片元数据先写入消息，再调用 `syncStreamingSnapshotToList()` 刷新短 Markdown 快照；该方法不设置终态幂等标记。
- [ ] 快照完成后立即请求可合并保存，终止事件再强制保存。
- [ ] 流式暂停期间仍保存图片元数据和快照，界面继续使用暂停前的冻结渲染状态，恢复后再显示新图片。
- [ ] 图片失败时追加短错误说明，不写入原始数据 URI。
- [ ] 修改图像模式现有归档回退规则，危险数据 URI 失败时不回退原值。
- [ ] 从 `MessageProcessor` 移除图片 Markdown 拼接，仅保留代码执行文本处理。
- [ ] 删除没有业务消费者的 `eventChannel`、`consumeAsFlow().sample()` 和 `trySend(appEvent)`，避免原始 Base64 事件在额外通道中滞留。
- [ ] 完成事件合并测试，确保同步后的短图片 Markdown不会被处理器最终文本覆盖。

新增以下内部纯函数：

```kotlin
internal fun applyGeneratedImageToMessage(
    message: Message,
    persistedSource: String,
): Message
```

该函数负责：

- 规范化地址。
- 判断是否已存在。
- 更新 `imageUrls`。
- 追加一次 Markdown。
- 设置 `contentStarted`。
- 保留既有文本、推理、来源和状态字段。

### 阶段 4：恢复图片点击并修复预览选择

优先级：P1。

目标：当前 MikePenz 渲染链路能够把被点击地址传回页面，预览集合准确。

任务：

- [ ] 给 `UnifiedMarkdownRenderer()` 增加可空 `onImageClick` 参数。
- [ ] 给 `MikePenzMarkdownRenderer()` 增加同名参数。
- [ ] 使用 `rememberUpdatedState()` 保存最新回调，避免 `remember` 组件树捕获旧闭包。
- [ ] `MarkdownInlineImageWithFailure()` 在成功图片上增加点击 Modifier 和无障碍点击语义。
- [ ] `AiMessageItem()` 把现有 `onImageClick` 传给统一 Markdown 入口。
- [ ] 新增纯函数 `buildImagePreviewSelection()`。
- [ ] `ChatScreen` 直接从 `messages` 构建候选集合，覆盖普通、代码和流式 AI 消息。
- [ ] 移除 `indexOf(...).coerceAtLeast(0)`。
- [ ] `AppViewModel.showImageViewer(urls, index)` 增加空列表和索引保护。

地址归一化规则：

| 地址类型 | 比较形式 |
|---|---|
| 绝对路径 | 规范化分隔符后的绝对路径 |
| `file://` | 解码 URI path 后转为绝对路径 |
| `content://` | scheme 小写，保留 authority、path 和 query |
| HTTP(S) | scheme 和 host 小写，保留 path、query、fragment |
| data URI | 去除 MIME 头附近允许的空白；只用于旧数据回退比较，不写入新候选元数据 |

### 阶段 5：旧数据迁移和加载隔离

优先级：P1。

目标：旧文本会话能够恢复生成图，并避免一个加载错误清空全部内存状态。

迁移范围：

- 文本历史会话。
- 文本最后打开会话。
- 图像历史和最后打开会话。
- `Message.text` 中的图片 Markdown。
- `Message.imageUrls` 中的数据 URI。启动迁移不主动下载已经安全的短远程 URL。
- 旧 `MarkdownPart.InlineImage.base64Data`。

迁移算法：

```text
读取一条消息
    |
    v
扫描 text、parts、imageUrls 中的图片来源
    |
    v
按原始来源在消息内去重
    |
    v
逐张归档到临时文件
    |
    +---------------------+
    |                     |
    v                     v
全部成功                 任一失败
替换正文地址             删除本轮临时文件
重建 imageUrls           数据库原消息不回写
移除 parts Base64         内存和数据库保留原消息
    |                     显示部分历史迁移失败警告
    |                     记录脱敏错误
    v
按会话事务回写 Room
```

幂等要求：

- 本地存在的有效文件不重复复制。
- 已替换为短路径的正文下次加载不再变化。
- 启动迁移只处理 Base64，不对普通 HTTP(S) 地址发起网络请求。
- 同一消息中相同数据 URI 只生成一个文件。
- 只有消息内容实际变化时才回写。
- 写回失败不删除原数据库内容。
- 新文件在数据库回写失败后交给现有孤儿附件清理流程。
- 单条消息迁移失败时，内存和数据库都保留原消息，避免后续全量保存用占位覆盖原数据；外部传输安全阀继续生效，下次启动再次尝试。

加载隔离：

- API 配置、文本历史、图像历史、文本最后打开会话、图像最后打开会话分别捕获异常。
- `RoomDataSource` 为历史加载返回会话列表和失败会话 ID，单个会话反序列化失败时继续加载其余会话。
- 新增内部类型 `LoadedHistorySession(sessionId, messages)` 和 `SessionHistoryLoadResult(sessions, failedSessionIds)`；既有列表接口可映射 `sessions.messages`，避免无关调用方改签名。
- 新增只更新单个既有会话的迁移写回入口，内部调用 `ChatDao.saveSessionWithMessages()`，不执行缺失会话删除扫描。
- `DataPersistenceManager.loadInitialData()` 增加 `onLoadWarning: (String) -> Unit = {}`，`AppViewModel` 将警告转成现有 Snackbar。
- `DataPersistenceManager` 在本次进程内分别保存文本和图像模式的 `failedSessionIds` 保护集合。
- 文本和图像最后打开会话若加载失败，分别设置进程内保护标记，禁止本次运行中的自动覆盖和自动清空。
- 每个捕获点先检查并重新抛出 `CancellationException`。
- 文本历史失败只影响文本历史，不清空配置和图像历史。
- 最后打开会话失败不覆盖已经成功加载的历史列表。
- 存在失败会话时显示“部分历史加载失败，原数据已保留”，日志只记录失败会话 ID 和异常类型。
- 存在图片迁移失败时显示“部分历史图片迁移失败，原数据已保留”，同一次启动每类警告最多显示一次。
- 迁移回写禁止调用具有“输入列表为全部真值”语义的 `saveChatHistory()` 或 `saveImageGenerationHistory()`，避免把加载失败的会话当作已删除会话。
- 单会话写回使用加载时保存的原始 `sessionId`，禁止根据迁移后的消息重新推导 ID。
- 后续普通全量保存把保护集合传给 `RoomDataSource.saveSessions()`，删除扫描必须跳过保护 ID。
- 同一会话在后续加载成功后从保护集合移除；用户明确执行“清空全部历史”时允许删除保护会话。
- 最后打开会话保护标记只阻止自动 `saveLastOpenChat()` 和 `clearLastOpenChat()`；用户明确清空历史时允许删除特殊会话记录。
- 保护期间普通历史保存继续执行，确保本次运行中新建和编辑的会话仍能持久化。
- `onLoadingComplete()` 必须恰好调用一次。
- 不在没有数据库证据时执行删除或自动清空会话。

### 阶段 6：完整验证

优先级：P1。

自动化验证顺序：

```powershell
cd app1

./gradlew :app:testDebugUnitTest --tests "*MessageImageContentTest*"
./gradlew :app:testDebugUnitTest --tests "*GeneratedImagePersistenceTest*"
./gradlew :app:testDebugUnitTest --tests "*ApiHandlerGeneratedImageTest*"
./gradlew :app:testDebugUnitTest --tests "*ApiHandlerStreamCompletionMergeTest*"
./gradlew :app:testDebugUnitTest --tests "*MessageProcessorFinalizePartsTest*"
./gradlew :app:testDebugUnitTest --tests "*ChatScreenImagePreviewSelectionTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownImageClickTest*"
./gradlew :app:testDebugUnitTest --tests "*DataPersistenceInlineImageMigrationTest*"
./gradlew :app:testDebugUnitTest --tests "*RoomDataSourcePartialHistoryLoadTest*"

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

由于涉及 Compose UI、文件系统和持久化，最终必须执行 `assembleDebug`。

## 10. 文件级改动计划

### 10.1 新增文件

| 文件 | 改动 |
|---|---|
| `app1/app/src/main/java/com/android/everytalk/util/message/MessageImageContent.kt` | 线性扫描 Markdown 图片、裸数据 URI、替换图片地址、生成外部传输文本 |
| `app1/app/src/main/java/com/android/everytalk/ui/components/image/ImagePreviewSelection.kt` | 地址归一化、候选去重、预览集合和索引选择 |
| `app1/app/src/test/java/com/android/everytalk/util/message/MessageImageContentTest.kt` | 净化、替换、Unicode 截断、畸形输入测试 |
| `app1/app/src/test/java/com/android/everytalk/util/storage/GeneratedImagePersistenceTest.kt` | 数据 URI 校验、大小限制、落盘和取消测试 |
| `app1/app/src/test/java/com/android/everytalk/statecontroller/ApiHandlerGeneratedImageTest.kt` | 消息图片状态纯函数、去重和失败规则测试 |
| `app1/app/src/test/java/com/android/everytalk/ui/components/image/ChatScreenImagePreviewSelectionTest.kt` | 地址匹配、未匹配回退、多图顺序测试 |
| `app1/app/src/test/java/com/android/everytalk/ui/components/markdown/MarkdownImageClickTest.kt` | Compose 图片点击回调和无障碍语义测试 |
| `app1/app/src/test/java/com/android/everytalk/ui/screens/viewmodel/DataPersistenceInlineImageMigrationTest.kt` | 旧消息迁移、幂等和失败不回写测试 |
| `app1/app/src/test/java/com/android/everytalk/data/database/RoomDataSourcePartialHistoryLoadTest.kt` | 单个坏会话不阻断其他会话加载，并返回失败会话 ID |

### 10.2 修改文件

| 文件 | 函数或区域 | 预期改动 |
|---|---|---|
| [`FileManager.kt`](../app1/app/src/main/java/com/android/everytalk/util/storage/FileManager.kt) | `loadBytesFromFlexibleSource()`、`saveBytesToInternalImages()` | 增加大小参数、MIME 校验、临时文件、取消传播、脱敏日志 |
| [`DataPersistenceManager.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/viewmodel/DataPersistenceManager.kt) | `persistInlineAndRemoteImages()`、`persistImageImmediate()`、`loadInitialData()`、保存函数 | 统一图片归档，迁移文本模式和旧 parts，缩小异常影响范围，并维护加载失败会话保护集合 |
| [`HistoryManager.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/viewmodel/HistoryManager.kt) | 新增窄委托方法 | 让 `ApiHandler` 使用持久化能力，不暴露 `persistenceManager` 字段 |
| [`ApiHandler.kt`](../app1/app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt) | `processStreamEvent()`、`archiveImageUrlsForMessage()`、空事件通道 | 图片先落盘，在同一主线程状态段更新正文和 `imageUrls`，处理两类图片事件，移除危险回退和无效 Base64 通道 |
| [`MessageProcessor.kt`](../app1/app/src/main/java/com/android/everytalk/util/messageprocessor/MessageProcessor.kt) | `CodeExecutionResult` 分支 | 移除图片 Markdown 拼接，保留文本代码执行结果 |
| [`ClipboardController.kt`](../app1/app/src/main/java/com/android/everytalk/statecontroller/controller/media/ClipboardController.kt) | `copyToClipboard()`、`exportMessageText()` | 统一调用净化器，处理系统调用失败 |
| [`StreamBlocksRenderer.kt`](../app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt) | `UnifiedMarkdownRenderer()` | 传播图片点击回调 |
| [`MikePenzMarkdownRenderer.kt`](../app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MikePenzMarkdownRenderer.kt) | 渲染入口和 `MarkdownInlineImageWithFailure()` | 图片点击、无障碍语义、最新回调引用 |
| [`ChatMessagesList.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt) | `AiMessageItem()`、`AiMessageFooterItem()` | 传播点击回调，分享前净化文本和捕获失败 |
| [`ChatScreen.kt`](../app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/ChatScreen.kt) | `onImageClick` | 使用纯预览选择函数，移除错误索引回退 |
| [`AppViewModel.kt`](../app1/app/src/main/java/com/android/everytalk/statecontroller/AppViewModel.kt) | `showImageViewer()`、初始加载调用 | 空列表和索引边界保护，把部分历史加载警告转为 Snackbar |
| [`RoomDataSource.kt`](../app1/app/src/main/java/com/android/everytalk/data/database/RoomDataSource.kt) | `loadSessions()`、`saveSessions()`、历史加载入口、单会话写回入口 | 返回带原始 session ID 的成功会话和失败会话 ID；迁移只更新成功会话，普通同步跳过受保护失败会话 |
| [`ApiHandlerStreamCompletionMergeTest.kt`](../app1/app/src/test/java/com/android/everytalk/statecontroller/ApiHandlerStreamCompletionMergeTest.kt) | 合并测试 | 确保短图片 Markdown 和 `imageUrls` 保留 |
| [`MessageProcessorFinalizePartsTest.kt`](../app1/app/src/test/java/com/android/everytalk/util/messageprocessor/MessageProcessorFinalizePartsTest.kt) | 流事件测试 | 确保图片不再进入处理器文本缓冲 |
| [`MarkdownEngineOwnershipTest.kt`](../app1/app/src/test/java/com/android/everytalk/ui/components/markdown/MarkdownEngineOwnershipTest.kt) | 渲染入口约束 | 固化统一入口仍负责传播图片点击 |

### 10.3 不修改文件

- `Message.kt` 不新增字段。
- `ChatEntities.kt` 不新增列。
- `AppDatabase` 不升级版本。
- Provider Registry 和 Provider 选择逻辑不修改。
- 图像预览 Dialog 的视觉实现不修改。

## 11. 扫描和替换算法要求

### 11.1 为什么不用单个大正则

Base64 文本可能达到数百万字符。带有跨行、非贪婪或嵌套分组的正则会产生额外字符串和回溯风险。实现采用单次线性扫描。

### 11.2 Markdown 图片识别

扫描器识别：

```markdown
![alt](destination)
![alt](<destination>)
```

规则：

- 支持转义字符。
- destination 中的括号按深度计数。
- 保存 token 的起止位置和 destination 的起止位置。
- 扫描失败时前进一个字符，禁止死循环。
- 只删除图片节点，不删除普通 `[text](url)`。

### 11.3 裸数据 URI 识别

Markdown 图片识别完成后，再扫描未覆盖区域中的 `data:image/`。识别到 `;base64,` 后允许 Base64 字符和 ASCII 空白，遇到右括号、尖括号、引号、反引号或其他结构字符结束。由于畸形裸数据 URI 无法可靠区分后续英文正文，找不到结构结束符时删除到文本末尾，优先保证不会把图片载荷交给 Binder。

净化完成后再次执行大小受限的大小写不敏感检查。若结果仍含 `data:image`，从残留标记删除到文本末尾，并在单元测试中固定该安全兜底。

### 11.4 UTF-8 截断

- 上限为 `256 * 1024` 字节。
- 从头逐个 Unicode code point 累加 UTF-8 长度。
- 为截断标记预留字节。
- 禁止截断 UTF-16 代理对。
- 返回值再次计算 UTF-8 长度并断言不超过上限。

## 12. 异常和取消处理

| 路径 | 可能故障 | 处理 | 用户可见结果 | 测试 |
|---|---|---|---|---|
| Base64 头解析 | MIME 缺失或格式畸形 | 拒绝，不解码 | 图片保存失败说明 | 有 |
| Base64 大小检查 | 超过 16MiB | 拒绝，不分配解码数组 | 图片过大或保存失败说明 | 有 |
| Base64 解码 | 非法字符 | 捕获格式异常 | 图片保存失败说明 | 有 |
| 远程下载 | 超时或非 2xx | 数据 URI 不回退；短远程 URL 可保留 | 图片远程加载或失败占位 | 有 |
| 文件写入 | 磁盘满、权限或 I/O 错误 | 删除临时文件，返回失败 | 图片保存失败说明 | 有 |
| 协程取消 | 页面切换或请求取消 | 删除临时文件，重新抛出取消 | 无额外错误弹窗 | 有 |
| 消息已被移除 | 落盘完成后找不到消息 ID | 删除新建孤儿文件或交给清理器 | 无错误消息污染其他会话 | 有 |
| 重复图片事件 | Provider 重发相同事件 | 地址归一化后去重 | 只显示一张 | 有 |
| Markdown 点击 | 回调为空 | 图片仍正常显示 | 无预览动作 | 有 |
| 预览候选缺失 | 被点击地址不在元数据 | 单图打开被点击地址 | 正确图片 | 有 |
| 剪贴板系统调用 | Binder 或系统服务失败 | 捕获异常 | Snackbar 显示复制失败 | 有 |
| 分享 Activity | 没有可处理 Activity 或系统失败 | 捕获异常 | Snackbar 显示分享失败 | 有 |
| 外部文本净化 | 数兆旧 Base64 导致扫描耗时 | `Dispatchers.Default` 执行，主线程只做系统调用 | 操作完成后显示结果 | 有 |
| 旧消息迁移 | 任一图片落盘失败 | 不回写，内存和数据库保留原消息 | Snackbar 提示部分历史迁移失败 | 有 |
| Room 回写 | 事务失败 | 原会话保持不变 | 下次启动重试 | 有 |
| 初始加载 | 某阶段异常 | 只降级对应阶段 | Snackbar 提示失败阶段，其他已加载数据保留 | 有 |

计划实施后，上表没有同时缺少测试、错误处理和用户反馈的静默故障路径。

## 13. 安全要求

1. 实时 Provider 图片来源只接受 `data:image` 和 HTTP(S)。
2. `data:` MIME 必须以 `image/` 开头。
3. 远程响应 MIME 必须为图片，并校验 PNG、JPEG、WebP、GIF、HEIF 支持格式的基础文件签名；不支持的格式返回失败。
4. 解码后图片上限为 16MiB。
5. 下载必须设置连接和读取超时。
6. 实时远程归档使用 15 秒总超时；启动历史迁移禁止远程下载。
7. 文件名只使用消息 ID、序号、内容摘要和安全扩展名。
8. 写入目录固定为应用私有 `filesDir/chat_attachments`。
9. 禁止把服务商返回的路径解释为任意本地绝对路径。
10. 禁止在日志、Snackbar、异常消息中输出 Base64 payload。
11. 日志中的 URL 最多记录 scheme、host 和字符长度，不记录敏感 query。
12. 所有外部文本在进入 Binder 前完成净化和字节限制。
13. 迁移过程中不删除原数据库消息，成功回写后再由现有孤儿文件清理机制处理旧文件。

## 14. 性能和内存预算

### 14.1 现状成本

原始图片大小为 `N` 字节时：

- Base64 字符数约为 `4N/3`。
- Kotlin `String` 在运行时可能占用接近每字符 1 至 2 字节，具体取决于实现。
- Base64 解码又产生约 `N` 字节数组。
- Markdown 解析、流式缓冲和 Room 参数绑定会产生额外对象和复制。

约 768KiB 原始图片编码后已经接近 1MiB 字符载荷，尚未计算 `Intent`、`ClipData` 和同时进行的 Binder 事务开销。

### 14.2 目标预算

| 项目 | 门槛 |
|---|---|
| 外部复制和分享文本 | 最大 256KiB UTF-8 |
| 单张实时生成图片 | 解码后最大 16MiB |
| 同时归档图片数 | 每条流按事件串行处理，一次一张 |
| 主线程文件 I/O | 0 |
| 主线程大文本扫描 | 0 |
| 新消息中的 Base64 | 0 字符 |
| 迁移重复执行 | 成功回写后为 0 |
| 归档失败日志 | 不包含图片正文 |

256KiB 上限为 UTF-8 字节值。即使 Android Parcel 使用更宽的字符串表示，加上 `Intent` 或 `ClipData` 元数据后仍为 1MB Binder 缓冲区保留明显余量。

### 14.3 性能验证

- 使用 1MiB、4MiB、16MiB 边界图片测试归档。
- 使用 16MiB 加 1 字节的估算载荷测试提前拒绝。
- 使用 5MiB Base64 旧消息测试净化函数耗时和内存。
- 使用 100 张小图的历史会话测试迁移顺序和幂等。
- 使用含 HTTP(S) 图片的旧历史验证启动期间不发起图片下载。
- 使用长中文和 emoji 文本验证 256KiB UTF-8 截断。
- 真机观察图片到达时主线程帧和内存峰值。

## 15. 测试矩阵

### 15.1 纯逻辑测试

| 场景 | 预期 |
|---|---|
| 普通文字 | 原样返回 |
| 普通 Markdown 链接 | 保留 |
| 单张 Base64 Markdown 图片 | 整个图片节点移除 |
| 多张 Base64 图片 | 全部移除，文字顺序不变 |
| 本地路径 Markdown 图片 | 外部文本中移除 |
| 裸数据 URI | 载荷移除 |
| 未闭合图片 Markdown | 不死循环，裸数据 URI 仍被移除 |
| 含换行的畸形 Base64 | 数据 URI 和后续不确定载荷被安全移除 |
| 中文和 emoji 超长文本 | 在完整 code point 边界截断 |
| 点击绝对路径，候选为 `file://` | 匹配成功 |
| 点击地址不在候选集合 | 单独打开被点击地址 |
| 候选列表含重复图 | 保持首次顺序并去重 |

### 15.2 流式状态测试

| 场景 | 预期 |
|---|---|
| 文本后收到一张图片 | 正文文字保留，追加短图片 Markdown，`imageUrls` 有一项 |
| 只收到图片 | `contentStarted=true`，图片可见，正文不含 Base64 |
| 连续收到两张图片 | 顺序一致，可左右滑动 |
| 同一事件重复 | 只保留一张 |
| 图片落盘失败 | 只追加短错误说明 |
| 图片后收到 Finish | 合并后短 Markdown 和 `imageUrls` 均保留 |
| 图片后用户取消 | 已落盘图片保存到当前会话，取消正常传播 |
| 流式暂停期间收到图片 | 本地状态和持久化保留图片，暂停界面不提前显示，恢复后出现 |
| `CodeExecutionResult` 同时有文本和图片 | 代码执行文本和图片都保留一次 |
| `ImageGeneration` 事件 | 与代码执行图片走同一处理函数 |

### 15.3 持久化测试

| 场景 | 预期 |
|---|---|
| 新文本会话保存 | Room 中没有 `data:image` |
| 新图像会话保存 | Room 中没有 `data:image` |
| 旧正文 Base64 迁移 | 正文改为本地路径，`imageUrls` 补齐 |
| 旧 `imageUrls` Base64 迁移 | 地址改为本地路径 |
| 旧 `InlineImage` part | Base64 落盘并从 parts 移除 |
| 第二次迁移 | 文件数和消息内容不变 |
| 单张迁移失败 | 原消息数据库记录不变 |
| 会话写回失败 | 事务回滚，原消息仍存在 |
| 部分会话加载失败后迁移成功项 | 失败会话仍保留在数据库，不被批量同步删除 |
| 部分会话加载失败后编辑其他会话 | 后续全量同步跳过保护 ID，失败会话仍保留 |
| 最后打开会话加载失败后继续聊天 | 自动保存和自动清空不覆盖失败的特殊会话记录 |
| 文本历史加载失败 | 图像历史和配置仍保留 |
| 最后打开会话失败 | 历史列表仍保留 |

### 15.4 Compose 和交互测试

| 场景 | 预期 |
|---|---|
| Markdown 图片加载成功 | 图片节点可点击 |
| 图片回调为空 | 图片正常显示且不崩溃 |
| 图片加载失败 | 显示现有失败占位 |
| 点击生成图 | 回调收到真实图片地址 |
| 多图预览 | 初始页等于被点击图索引 |
| 未匹配图片 | 不打开第一张参考图 |

### 15.5 回归测试

- 普通文本流式输出。
- 思考内容和正文合并。
- 代码块渲染和复制。
- Markdown 表格、链接、公式和脚注。
- 用户附件图片预览。
- 图像模式生成、预览、下载和重启恢复。
- 历史切换、重新回答和取消流式请求。

## 16. 真机验收步骤

### 16.1 Issue 原始路径

1. 在文本模式选择支持 Gemini 代码执行的配置。
2. 添加一张参考图。
3. 要求模型通过代码执行生成或处理图片。
4. 等待输出完成。
5. 点击输出图。
6. 确认打开输出图，不打开参考图。
7. 返回聊天，点击复制。
8. 粘贴到便签，确认只有文本，没有 Base64。
9. 点击分享，选择一个文本接收应用。
10. 确认应用不崩溃，分享内容没有 Base64。
11. 强制结束 EveryTalk 并重新启动。
12. 确认会话和输出图仍存在。

### 16.2 多图路径

1. 在同一条 AI 消息中生成两张图片。
2. 点击第二张图。
3. 确认预览初始页为第二张。
4. 左右滑动确认顺序和 `imageUrls` 一致。

### 16.3 旧数据路径

1. 使用含 Base64 图片的旧数据库副本启动。
2. 确认历史可加载。
3. 确认图片能够显示和点击。
4. 再次重启。
5. 确认没有重复生成文件，加载速度恢复正常。

### 16.4 图像模式回归

1. 在图像模式生成一张图。
2. 点击预览、滑动、下载。
3. 复制伴随文本。
4. 重启并恢复会话。
5. 确认图像模式没有出现数据 URI 回填。

### 16.5 日志确认

验收期间检查：

```powershell
adb logcat | Select-String -Pattern "TransactionTooLargeException|SQLiteBlobTooBigException|OutOfMemoryError|persistMessageImage|ImagePreview"
```

通过标准：

- 没有 `TransactionTooLargeException`。
- 没有数据库超大字段异常。
- 没有图片 Base64 出现在日志。
- 没有主线程文件 I/O 警告。

## 17. 风险表

| 风险 | 概率 | 影响 | 控制措施 |
|---|---|---|---|
| 当前未提交的 Markdown 改动与点击修复冲突 | 高 | 高 | 实施前保存完整 diff，只在当前工作树上做最小补丁，逐个文件复核 |
| 当前未提交的历史加载 Job 改动与异常隔离冲突 | 高 | 高 | 保留现有 generation 和取消语义，在其上缩小异常边界并补 `CancellationException` 传播测试 |
| 完成合并覆盖短图片 Markdown | 中 | 高 | 增加 `mergeStreamingCompletionMessage()` 图片回归测试 |
| 迁移扫描误删普通链接 | 低 | 高 | 仅识别 `![` 图片语法，普通链接测试固定 |
| 大 Base64 解码造成内存峰值 | 中 | 高 | 解码前估算，16MiB 上限，IO 串行处理 |
| 归档失败导致图片不可见 | 中 | 中 | HTTP 可保留短 URL，数据 URI 显示明确失败说明 |
| 迁移重复生成文件 | 中 | 中 | 消息内来源去重、成功回写、幂等测试 |
| 文件成功但 Room 回写失败产生孤儿 | 中 | 低 | 使用现有孤儿附件清理流程 |
| 图片点击 Modifier 影响文字选择 | 低 | 中 | 点击只附加在图片节点，Compose 交互测试 |
| 预览地址归一化错误 | 中 | 中 | 各 scheme 纯函数测试，未匹配单图回退 |
| 外部文本截断损坏 Unicode | 低 | 中 | 按 code point 和 UTF-8 字节截断 |
| 部分加载后后续全量保存误删失败会话 | 中 | 高 | 迁移使用单会话写回，普通同步传递保护 ID 并跳过删除，数据库测试覆盖两条路径 |
| 部分会话加载失败后用户不知情 | 低 | 中 | 返回失败会话 ID，并通过现有 Snackbar 显示部分历史加载失败 |

## 18. 回滚方案

实施时按以下顺序拆分，任一阶段可独立回滚：

1. 外部传输安全阀。
2. 统一图片归档。
3. 流式图片状态修复。
4. Markdown 点击和预览选择。
5. 旧数据迁移和加载隔离。
6. 测试补全。

回滚约束：

- 不执行数据库降级，因为本方案不修改 schema。
- 已落盘图片继续保留，旧代码能够读取本地绝对路径和 `imageUrls`。
- 迁移回写后的正文仍是合法 Markdown。
- 回滚迁移代码不会恢复已移除的 Base64，但本地图片文件保留了等价内容。
- 外部传输安全阀应最后回滚，便于旧 Base64 消息继续得到保护。

## 19. 完成标准

只有同时满足以下条件才能关闭 Issue：

- [ ] 文本模式生成图进入本地文件和 `imageUrls`。
- [ ] 新消息的 `text`、`parts`、`imageUrls` 和 Room 记录均不含 `data:image`。
- [ ] 点击生成图打开自身。
- [ ] 多图预览初始索引准确。
- [ ] 当前 MikePenz 图片节点能够传播点击回调。
- [ ] 复制、分享和文本导出不包含 Base64 图片。
- [ ] 复制和分享 UTF-8 载荷不超过 256KiB。
- [ ] 旧消息迁移幂等，失败不破坏原数据库记录。
- [ ] 单阶段加载失败不清空其他已成功加载状态。
- [ ] 所有图片 I/O 不在主线程执行。
- [ ] 所有新增协程路径传播 `CancellationException`。
- [ ] 日志没有图片 payload。
- [ ] 相关定向测试通过。
- [ ] 全量单元测试通过。
- [ ] `assembleDebug` 和 `lintDebug` 通过。
- [ ] 文本模式和图像模式真机回归通过。
- [ ] logcat 没有超大 Binder 事务异常。

## 20. 工程自审

### 20.1 范围审查

结论：接受完整修复范围。只处理复制或预览会留下大文本持久化和恢复风险，只处理落盘又无法保护旧数据。五个实施阶段属于同一根因的必要闭环。

明确排除模型能力限制、数据库 schema 调整和 UI 重设计，避免扩大范围。

### 20.2 架构审查

发现并纳入方案的 5 项问题：

1. 二进制图片与文本字段混合。
2. `MessageProcessor` 和 `ApiHandler` 同时负责图片正文。
3. 文本模式和图像模式持久化策略不一致。
4. 渲染图片地址与预览元数据来源不一致。
5. 初始加载异常边界过大。

审查结果：全部已折叠进目标职责和阶段计划，无开放架构决策。

### 20.3 代码质量审查

发现并纳入方案的 7 项问题：

1. 图片归档逻辑存在重复实现。
2. `coerceAtLeast(0)` 隐藏未匹配状态。
3. 分享在 UI 中直接构造超大 `Intent`。
4. 文件读取异常日志可能包含完整来源。
5. 多处 `catch (Exception)` 会吞掉协程取消。
6. 当前 Markdown 图片点击参数在重构后断链。
7. 原始流事件被写入无业务消费者的通道，Base64 图片会额外滞留。

审查结果：采用一个归档入口、一个外部净化入口和一个预览选择纯函数，未新增第三方依赖。

### 20.4 测试审查

测试数据流：

```text
Provider 事件测试
    |
    v
图片归档测试
    |
    v
消息状态纯函数测试
    |
    v
完成合并测试
    |
    +-------------------+
    |                   |
    v                   v
持久化迁移测试       Markdown 点击测试
    |                   |
    v                   v
重启恢复测试         预览选择测试
    |                   |
    +---------+---------+
              |
              v
       复制和分享安全测试
```

发现并纳入方案的 9 个测试缺口：

1. 文本模式图片事件。
2. 新消息无 Base64 不变量。
3. 图片归档失败。
4. 完成合并保留图片。
5. 外部文本净化和字节上限。
6. Markdown 图片点击。
7. 未匹配预览回退。
8. 旧数据迁移幂等。
9. 图像模式和加载隔离回归。

审查结果：测试矩阵覆盖正常、边界、失败、取消和历史兼容路径，无静默关键缺口。

### 20.5 性能审查

发现并纳入方案的 5 项问题：

1. Base64 带来约三分之一编码膨胀。
2. 大字符串同时经过流式缓冲、Markdown 和 Room。
3. Binder 事务缓冲区只有 1MB，并且由进程共享。
4. 旧迁移若不回写会在每次启动重复执行。
5. 旧 Base64 文本的净化若放在主线程会造成明显卡顿。

审查结果：图片先落盘、外部文本 256KiB 上限、单图 16MiB 上限、迁移成功回写和串行 IO 已纳入方案。

### 20.6 失败模式审查

- 已列出 16 条生产失败模式。
- 每条路径均有测试计划。
- 每条路径均有错误处理。
- 用户相关失败均有可见结果或保留原数据策略。
- 关键缺口数量为 0。

### 20.7 已有能力复用

| 已有能力 | 复用方式 |
|---|---|
| `FileManager.loadBytesFromFlexibleSource()` | 增强大小、MIME、取消和日志规则后继续使用 |
| `FileManager.saveBytesToInternalImages()` | 增强临时文件和原子写入后继续使用 |
| `DataPersistenceManager.persistInlineAndRemoteImages()` | 扩展到文本模式、正文和旧 parts |
| `Message.imageUrls` | 作为预览和持久化的图片元数据，不新增字段 |
| `ImagePreviewDialog` | 保持视觉和缩放逻辑，只修正输入集合和索引 |
| `UnifiedMarkdownRenderer` | 保持唯一生产 Markdown 入口，只补图片回调 |
| `ChatDao.saveSessionWithMessages()` | 继续依赖 Room 事务保证会话写回原子性 |
| `cleanupOrphanedAttachments()` | 清理迁移和回写失败后的孤儿文件 |

### 20.8 回顾性检查

当前分支近期多次修改 Markdown、ChatScreen、ChatMessagesList、流式完成合并和历史加载 Job。Issue 修复会触及同一批高变动文件，因此实施必须保留现有 history load generation、取消和滚动语义，逐阶段运行现有渲染、滚动、流式完成、历史加载测试，禁止覆盖当前工作树中的未提交改动。

### 20.9 实施并行性

Sequential implementation, no parallelization opportunity.

原因：归档、流式状态、Markdown 点击和预览最终都汇聚到 `ApiHandler`、`ChatMessagesList`、`ChatScreen` 和当前未提交的 Markdown 工作树。并行工作树会提高合并冲突和覆盖用户改动的风险。

### 20.10 自审摘要

| 项目 | 结果 |
|---|---|
| Scope Challenge | 接受完整闭环范围 |
| Architecture Review | 5 项问题，全部纳入方案 |
| Code Quality Review | 7 项问题，全部纳入方案 |
| Test Review | 已生成测试数据流，9 个缺口全部覆盖 |
| Performance Review | 5 项问题，全部纳入预算和门槛 |
| NOT in scope | 已写明 |
| What already exists | 已写明并优先复用 |
| TODOS.md updates | 0 项，未把必要修复延后 |
| Failure modes | 0 个未覆盖关键缺口 |
| Outside voice | 跳过，用户要求当前模型先完成自审，且本任务禁止启动子代理 |
| Parallelization | 顺序实施 |
| Autoplan JSONL | 本机未安装 `jq`，按评审工具规则跳过；不影响方案文档和 Review Log |
| 完整方案选择 | 26 项审查发现全部进入实施计划 |

## 21. Implementation Tasks

以下任务由工程自审发现直接生成。

- [ ] **T1（P1，人工约 1 小时，Codex 约 15 分钟）**：外部传输安全，加入图片净化和 256KiB 上限
  - 来源：代码质量审查第 3 项，性能审查第 3、5 项
  - 文件：`MessageImageContent.kt`、`ClipboardController.kt`、`ChatMessagesList.kt`
  - 验证：`./gradlew :app:testDebugUnitTest --tests "*MessageImageContentTest*"`
- [ ] **T2（P1，人工约 3 小时，Codex 约 30 分钟）**：存储层，统一图片来源校验和归档
  - 来源：架构审查第 3 项，代码质量审查第 1、4、5 项
  - 文件：`FileManager.kt`、`DataPersistenceManager.kt`、`HistoryManager.kt`
  - 验证：`./gradlew :app:testDebugUnitTest --tests "*GeneratedImagePersistenceTest*"`
- [ ] **T3（P1，人工约 3 小时，Codex 约 30 分钟）**：流式状态，图片先落盘并在同一主线程状态段更新消息
  - 来源：架构审查第 1、2 项，代码质量审查第 7 项
  - 文件：`ApiHandler.kt`、`MessageProcessor.kt`
  - 验证：`./gradlew :app:testDebugUnitTest --tests "*ApiHandlerGeneratedImageTest*" --tests "*ApiHandlerStreamCompletionMergeTest*" --tests "*MessageProcessorFinalizePartsTest*"`
- [ ] **T4（P1，人工约 2 小时，Codex 约 20 分钟）**：图片交互，恢复点击回调并修正预览索引
  - 来源：架构审查第 4 项，代码质量审查第 2、6 项
  - 文件：`StreamBlocksRenderer.kt`、`MikePenzMarkdownRenderer.kt`、`ChatMessagesList.kt`、`ChatScreen.kt`、`AppViewModel.kt`
  - 验证：`./gradlew :app:testDebugUnitTest --tests "*MarkdownImageClickTest*" --tests "*ChatScreenImagePreviewSelectionTest*"`
- [ ] **T5（P2，人工约 4 小时，Codex 约 40 分钟）**：历史兼容，迁移旧 Base64 图片并隔离加载错误
  - 来源：架构审查第 5 项，测试审查第 8、9 项
  - 文件：`DataPersistenceManager.kt`、`RoomDataSource.kt`、`AppViewModel.kt`
  - 验证：`./gradlew :app:testDebugUnitTest --tests "*DataPersistenceInlineImageMigrationTest*" --tests "*RoomDataSourcePartialHistoryLoadTest*"`
- [ ] **T6（P1，人工约 3 小时，Codex 约 30 分钟）**：回归验证，运行定向、全量、构建、Lint 和真机用例
  - 来源：测试审查全部缺口
  - 文件：相关测试文件，不修改无关生产代码
  - 验证：`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

## 22. 参考资料

- [Issue #104](https://github.com/roseforljh/EveryTalk/issues/104)
- [Android TransactionTooLargeException](https://developer.android.com/reference/android/os/TransactionTooLargeException)
- [Android ClipboardManager.setPrimaryClip](https://developer.android.com/reference/android/content/ClipboardManager#setPrimaryClip(android.content.ClipData))
- [Android Context.startActivity](https://developer.android.com/reference/android/content/Context#startActivity(android.content.Intent))
- [Gemini API Code execution](https://ai.google.dev/gemini-api/docs/code-execution)

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|---|---|---|---:|---|---|
| CEO Review | `/plan-ceo-review` | 范围和产品策略 | 0 | 未运行 | Bug 修复未改变产品方向 |
| Codex Review | `/codex review` | 独立第二意见 | 0 | 未运行 | 本轮由当前 Codex 完成内部自审，没有启动外部代理 |
| Eng Review | `/plan-eng-review` | 架构、代码、测试和性能 | 1 | CLEAR | 26 项问题全部纳入方案，0 个关键缺口 |
| Design Review | `/plan-design-review` | UI 和交互 | 0 | 未运行 | 只恢复现有图片点击和正确预览，不改变视觉设计 |
| DX Review | `/plan-devex-review` | 开发体验 | 0 | 未运行 | 本修复没有开发者工作流变更 |

**VERDICT:** ENG CLEARED，方案可以进入用户审查，尚未实施业务代码。

NO UNRESOLVED DECISIONS
