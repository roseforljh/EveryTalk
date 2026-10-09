# EveryTalk 3分钟面试路演分镜脚本与源码级攻防手册

> **项目定位**：基于 Kotlin 2.4 + Jetpack Compose 打造的 Android 原生全能 AI 对话平台。
> **核心标签**：全模型直连 · 流式自适应节流 (60FPS) · TopAnchor 视口防抖 · Thinking 思考链 · Anthropic MCP 移动端生态 · 隐私零泄露。

---

## 🎬 Part 1: 3分钟黄金路演分镜与逐字演讲稿

本脚本与配套的 1080P 高清视频 `everytalk_intro.mp4` 及交互式演示台 `artifacts/everytalk_presentation.html` 严格同步。

### 00:00 - 00:25 【分镜一：开场与项目定位】
* **画面**：EveryTalk 发光 Logo 呼吸浮现，核心技术栈（Kotlin 2.4 / Jetpack Compose / Ktor 3.5 / Room / Koin）与 5 大特性徽章展开。
* **口播逐字稿**：
  > “各位面试官好，我是候选人。今天为大家展示我主导研发的 Android 原生智能对话平台 —— **EveryTalk**。
  > 随着大模型快速迭代，移动端面临着模型协议割裂、高速流式打字卡顿、长文本阅读跳顶以及隐私顾虑等严峻挑战。
  > EveryTalk 采用现代 Android 架构（MVVM + 单向数据流 UDF），不仅实现了 OpenAI、Gemini、Claude、DeepSeek 等全模型无缝直连，更自研了流式自适应节流缓冲器与视口锚定引擎，将端侧 AI 交互体验做到了极致流畅与丝滑。”
* **源码映射**：`EveryTalkApplication.kt`, `MainActivity.kt`, `CLAUDE.md`

---

### 00:25 - 00:55 【分镜二：全模型接入层与高可用架构】
* **画面**：左侧展示模型切换弹窗与参数调节截图；右侧展示 Provider 适配器架构图与 Direct Mode 数据流向。
* **口播逐字稿**：
  > “在接入层设计上，我们面临的首要难题是不同模型 API 的异构性（如 OpenAI 的 Chat Completion/Responses、Anthropic Messages API 与 Gemini 多模态流）。
  > 我们通过 **Provider 适配器模式与策略注册表（ProviderRegistry）**，将所有上游协议统一归一化为密封类 `AppStreamEvent`。
  > 同时，客户端采用高性能 Ktor 3.5 实现了**无后端直连模式（Direct Mode）**，所有 API Key 与聊天记录均使用 Room 数据库加密保存在端侧，彻底杜绝数据上云的隐私泄露风险；并内置了多节点健康探测与 Cloudflare 拦截自动降级机制，保证高可用连接。”
* **源码映射**：`com.android.everytalk.provider.ProviderRegistry.kt`, `LLMProvider.kt`, `ApiClient.kt`

---

### 00:55 - 01:35 【分镜三：流式性能攻坚 —— StreamingBuffer 自适应节流】
* **画面**：左侧技术卡片对比高频重组与平滑输出，展示核心算法原理；右侧手机展示长文本高速生成丝滑无卡顿。
* **口播逐字稿**：
  > “这是整个客户端最核心的性能技术攻坚点：
  > 在真实业务中，高速推理模型（如 DeepSeek、o3-mini）流式输出速率高达每秒 1500 至 2000 字符。传统的做法是在接收到每个 SSE chunk 时直接更新 UI，这会导致 Compose `LazyColumn` 发生高频重组，引发主线程剧烈掉帧、卡顿和设备发热。
  > 为此，我们自研了 **`StreamingBuffer` 自适应节流与批量合并算法**：
  > 首先，**首包（First Token）立即直通刷新**，确保 TTFT 首屏响应零延迟感知；
  > 其次，系统动态采样实时字符速率（charsPerSecond），在高速流时**自适应提升合并阈值至最高 192 字符，刷新间隔动态调整为 180ms**；而在低速打字时缩短至 80ms 保持细腻。
  > 这套算法成功**降低了 85% 以上的不必要重组**，将长文本流式渲染帧率死死稳固在 60/120 FPS，CPU 与耗电显著下降。”
* **源码映射**：`com.android.everytalk.statecontroller.streaming.StreamingBuffer.kt`

---

### 01:35 - 02:05 【分镜四：视口体验攻坚 —— TopAnchor 视口锚定引擎】
* **画面**：展示用户向上翻看历史消息时的平滑交互，底部虚拟预留空间与像素补偿图解。
* **口播逐字稿**：
  > “另一个业界公认的交互顽疾是：流式输出时消息体持续变长，Compose 的 `LazyColumn` 极易出现跳顶、视口闪烁，尤其是**用户尝试上滑阅读历史记录时，新生成的文字会强制抢夺手势并推开屏幕**。
  > 为此，我们研发了 **`TopAnchor` 视口锚定引擎**。
  > 我们通过自研的布局修饰符 `appendTopAnchorReserve`，在 Compose 布局测量阶段精准预留推进像素，避免高频测量震颤；
  > 结合 `TopAnchorLazyListBridge` 滚动门控：当检测到用户上滑浏览历史时，**立即无感冻结当前视口**，让 AI 在底部的增量输出在后台静默累计，完全不打扰用户当前的阅读位置。”
* **源码映射**：`com.android.everytalk.ui.topanchor.TopAnchorReserveEngine.kt`, `TopAnchorLazyListBridge.kt`

---

### 02:05 - 02:35 【分镜五：Thinking 思考链与多模态矩阵】
* **画面**：手机端展示 DeepSeek-R1 思考链时间线展开、LaTeX 离线公式推导渲染、Flux.1 生图与实时语音波形。
* **口播逐字稿**：
  > “在深度交互体验上，EveryTalk 率先支持了 DeepSeek-R1、Claude 3.7 的 **Thinking 思维链实时流式解析**，通过状态呼吸灯与可折叠时间轴，让 AI 的反思推理过程一目了然；
  > 针对学术与专业场景，我们集成了 **MathJax 纯离线 SVG 渲染器**，毫秒级解析复杂的 LaTeX 块级公式与内联公式；
  > 在多模态维度，支持 Flux.1 与 Imagen 3 高清生图，以及基于 WebRTC / 双向音频流的 **Live Voice 全双工超低延迟语音交互**。”
* **源码映射**：`com.android.everytalk.ui.screens.BubbleMain.Main.ThinkingUI.kt`, `ThinkingExecutionTimeline.kt`, `MathJaxSvgRenderer.kt`

---

### 02:35 - 03:00 【分镜六：开放生态 MCP 与自动上下文压缩】
* **画面**：展示 MCP 工具面板、Terminal 终端执行与总结全景卡片。
* **口播逐字稿**：
  > “最后，EveryTalk 积极拥抱开放协议生态。我们率先在 Android 端深度集成了 **Anthropic MCP (Model Context Protocol)** 官方 SDK，支持 SSE 与 HTTP 双传输协议，赋予模型调用外部工具与本地 Bash 终端的能力，并内置 ToolLoop 循环熔断防护；
  > 同时实现了 **`AutoContextCompression` 自动上下文压缩机制**，基于滑动窗口与指纹快照，在超长多轮对话时自动提炼结构化摘要，保障上下文永不溢出。
  > 总结来说，EveryTalk 不仅是一款功能全面的客户端，更是对移动端大模型极致性能与前沿架构的深度实践。谢谢各位面试官！”
* **源码映射**：`com.android.everytalk.data.mcp.McpClientManager.kt`, `AutoContextCompression.kt`

---

## 🎯 Part 2: 面试官 5 大高频深度拷问与源码级答辩策略

### ❓ 考题 1：Compose 在高频流式输出（打字机）时很容易掉帧卡顿，你们是怎么定位和优化的？
* **得分关键词**：Recomposition（重组）、StreamingBuffer、自适应节流、批量合并、首包 0 延迟。
* **满分应答话术**：
  > “我们最初使用 Layout Inspector 和 `PerformanceMonitor` 跟踪时发现，当大模型以每秒 100+ 个 chunk 高速返回 SSE 事件时，如果在 ViewModel 中每个 chunk 都去触发 `StateFlow.update`，会导致 Compose 发生灾难性的密集重组（每秒上百次），使得主线程 Choreographer 丢帧严重。
  > 
  > 我们的解决方案分为两层：
  > 1. **在状态收集层引入 `StreamingBuffer`（自研自适应节流器）**：
  >    - **首包直通**：第一个 chunk 收到时立即执行 `performFlush()`，保证 TTFT（Time to First Token）零感知延迟；
  >    - **速率自适应节流**：在后续传输中，内部按 500ms 窗口动态统计字符生成速率（`charsPerSecond`）。当速率 > 2000 字/秒时，动态将刷新间隔步进拉长到 180ms，并将单次批量阈值提升至 192 字符；当速率 < 500 字/秒时，缩短间隔至 80ms 保证打字机视觉连续性。
  > 2. **在 UI 组件层做局部重组隔离**：
  >    - 我们将正在流式生成的 Message Item 抽象出独立的 `StreamingRenderState`，通过 `@Stable` 和精准的 StateKey 隔离，确保流式增量只重组当前这一个气泡中的文本控件，整个 LazyColumn 其他历史 item 完全不参与重组。
  > 最终重组次数降低了 85%，长文本生成全程稳定在 60 帧。”

---

### ❓ 考题 2：流式文本不断增多导致聊天列表跳动、用户向上翻看历史时冲突，怎么解决的？
* **得分关键词**：TopAnchorReserveEngine、appendTopAnchorReserve、用户滚动门控、像素精准补偿。
* **满分应答话术**：
  > “这是一个典型的移动端 AI 聊天交互痛点。在 Compose `LazyColumn` 中，随着底部文本动态拉长，列表整体高度每帧都在变化，如果简单调用 `animateScrollToItem`，会导致强烈的画面抖动；如果用户正往上滑想看上面某条历史消息，新来的流式消息会不断改变 offset 造成‘跳顶’甚至抢夺手势。
  > 
  > 我们通过自研的 **`TopAnchorReserveEngine`** 从根本上解决了这个问题：
  > 1. **布局层高度预留（Reserve Engine）**：通过自定义 Modifier `appendTopAnchorReserve(reservePx)`，在 Compose 测量布局阶段把预计的增量空间预留出来，避免每次字符流入时频繁重算高度导致布局抖动；
  > 2. **滚动门控与状态桥接（`TopAnchorLazyListBridge`）**：监听列表滚动状态。当检测到 `isScrollInProgress` 且用户处于阅读历史阶段时，自动切断底部的吸附逻辑，激活‘视口锚定锁’，此时 AI 即使生成数千字，也只在视口下方默默累积，当前屏幕显示的内容像素绝对不动；只有当用户重新主动划到底部，或者点击‘回到最新’悬浮钮时，才平滑解除锁定。”

---

### ❓ 考题 3：OpenAI、Gemini、Claude 和 DeepSeek 各家的流式协议与参数差异巨大，客户端如何优雅扩展？
* **得分关键词**：Provider 策略模式、Sealed Class 领域事件流、PiMessageTransformer、归一化。
* **满分应答话术**：
  > “我们在架构上严格遵循开闭原则（OCP），采用了两层抽象：
  > 1. **领域模型与统一事件流**：
  >    - 定义了密封类 `sealed class AppStreamEvent`，包括 `Text`、`Reasoning`（思维链）、`ToolCall`（工具调用参数增量）、`Usage`（Token统计）等通用事件，UI 层只认 `AppStreamEvent`，完全对底层模型解耦；
  > 2. **接入层适配器模式（`ProviderRegistry`）**：
  >    - 定义接口 `LLMProvider`，派生出 `OpenAICompatibleProvider`、`GeminiProvider`、`AnthropicProvider`。
  >    - 通过 `PiMessageTransformer` 对请求上下文进行转换（如 Anthropic 的 `system` 参数独立、Gemini 的 `contents/parts` 结构、OpenAI 的 `messages` 数组）；
  >    - 对返回的 SSE 数据流，由各自 Provider 内部的 Parser 转换为标准的 `AppStreamEvent` 向上 emit。
  > 当未来需要接入新的大模型（如 Grok 或 Minimax）时，只需增加一个新的 Provider 实现类并在注册表中配置路由规则，上层 UI 和业务逻辑无需改动一行代码。”

---

### ❓ 考题 4：面对超长对话（几十轮），如何防止客户端内存泄漏以及避免超过模型 Context Window？
* **得分关键词**：AutoContextCompression、滑动窗口、指纹校验快照、结构化摘要、Room 分页。
* **满分应答话术**：
  > “我们设计了双重机制保障长对话的稳定：
  > 1. **数据层**：利用 Room + Paging 机制，聊天历史采用按需加载，内存中只保留当前视口及其前后的有限条目，图片与附件均采用本地 URI 引用，不把 Bitmap 常驻内存；
  > 2. **上下文智能压缩引擎（`AutoContextCompression`）**：
  >    - 当会话估算 Token 接近设定阈值（如模型窗口的 75%）时，自动触发后台压缩；
  >    - 采用**滑动窗口策略**：保留最近 25% 的高保真最新交互，将更早的前序对话发送给摘要模型生成结构化摘要（涵盖‘用户目标’、‘约束与偏好’、‘关键事实与工具产物’、‘未完成事项’）；
  >    - 配合**指纹快照（Prefix Fingerprint Checkpoint）**：确保摘要的一致性，防止由于网络重试导致上下文重复压缩或丢失记忆。从而在端侧实现了‘无限轮次对话’的假象，而实际请求 Token 始终保持在健康区间。”

---

### ❓ 考题 5：你们在移动端落地 Anthropic MCP (Model Context Protocol) 协议有什么技术难点与安全性考虑？
* **得分关键词**：MCP Kotlin SDK、SSE/Streamable HTTP Transport、别名哈希隔离、ToolLoop 递归熔断。
* **满分应答话术**：
  > “MCP 通常在桌面端或服务端运行，在 Android 移动端落地主要面临**长连接稳定性、工具调用安全与多工具命名冲突**三大难题：
  > 1. **双传输协议支持**：我们基于 `io.modelcontextprotocol.kotlin.sdk`，使用 Ktor 实现了 `SseClientTransport` 和 `StreamableHttpClientTransport`，适配云端与内网环境下的各种 MCP Server；
  > 2. **工具命名空间隔离（`buildMcpToolAlias`）**：由于不同 MCP Server 可能提供同名工具（如 `read_file`），我们在端侧利用 SHA-256 哈希加 ServerId 生成安全别名（`mcp_{hash}_{toolName}`），抹平重名风险；
  > 3. **安全守护与死循环熔断（`ToolLoopContextGuard`）**：移动端电量与流量敏感，模型在 ReAct 循环中如果陷入工具调用死循环代价极高。我们设置了最大迭代轮次限制（Max Turns Guard）与用户权限确认弹窗（Approval Gate），对于敏感操作（如执行 Shell 或修改本地文件）必须经用户显式确认才放行。”

---

## 💡 面试加分绝招

1. **主动引导面试官看细节**：
   - “如果在座各位老师对 `StreamingBuffer` 的自适应速率调节算法感兴趣，我可以调出 `StreamingBuffer.kt` 核心代码为您深入推演其状态流转。”
2. **强调‘工程化思维’**：
   - 突出自己不仅是调 API，而是在**解决高频并发渲染性能、视口跳跃冲突、网络异常降级、隐私安全等真实现场工程难题**。
