# Android Streamdown Parity Architecture Plan

## 1. 架构目标

EveryTalk 要做的是 Android 原生 Streamdown 等效层。它需要具备三层能力：

1. 流式修补：对不完整 Markdown 做只读显示修补。
2. 结构化解析：把 Markdown 变成稳定节点，而非每帧交给 Markwon 猜。
3. 插件渲染：代码、数学、Mermaid、链接安全、CJK 和控件独立演进。

## 2. 现有链路

当前 AI 正文主链路：

```text
EnhancedMarkdownText
-> ContentCoordinator
-> TableAwareText
-> StreamBlocksRenderer 或 MarkdownRenderer
-> Compose 原生节点 或 Markwon TextView fallback
```

当前还存在直接流式块渲染链路：

```text
StreamingMessageStateManager
-> buildStreamingRenderState / buildStreamingRenderStateIncremental
-> ChatMessagesList / ImageGenerationMessagesList / BubbleContentTypes
-> StreamBlocksRenderer
-> NativeMarkdownBlocksSegment 或 block segments
```

已有基础：

- `StreamBlockParser` 已能识别代码块、数学、普通文本。
- `StreamingRenderState` 已有 committed/tail 拆分。
- `StreamBlocksRenderer` 已包含原生 block Markdown 渲染。
- `TableAwareText` 已做表格、代码、数学的路由。
- `MarkdownRenderer` 已作为 Markwon fallback。
- `CodeBlockCard` 已包含复制、预览、语法高亮。
- `StableLatexRenderer` 已支持公式渲染。

主要问题：

- 解析逻辑分散在 UI 文件里。
- 流式修补没有完整对齐 remend。
- Markdown 节点模型没有正式边界。
- Mermaid 缺失。
- 链接安全和 HTML 白名单没有作为插件策略统一管理。
- fallback 原因没有形成可量化指标。

## 3. 目标包结构

新增包建议如下：

```text
app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/
  StreamdownMarkdown.kt
  StreamdownFeatureFlags.kt
  StreamdownRenderConfig.kt
  StreamdownRenderInput.kt
  StreamdownRenderState.kt
  StreamdownRenderer.kt
  StreamdownTelemetry.kt
  StreamdownAndroidSlots.kt

app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/
  StreamingMarkdownRepairer.kt
  MarkdownDelimiterScanner.kt
  StreamingRepairPolicy.kt
  StreamingRepairResult.kt
  StreamingRepairHandler.kt

app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/
  MarkdownBlockNode.kt
  MarkdownInlineNode.kt
  MarkdownNodeRange.kt
  MarkdownAstParser.kt
  MarkdownAstFallbackReason.kt
  MarkdownStableId.kt

app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/
  MarkdownPlugin.kt
  CodeMarkdownPlugin.kt
  MathMarkdownPlugin.kt
  MermaidMarkdownPlugin.kt
  CjkMarkdownPlugin.kt
  LinkSafetyPlugin.kt
  HtmlSecurityPlugin.kt
  UrlTransformPlugin.kt
  AndroidSlotPlugin.kt
  TableControlsPlugin.kt
  ShikiParityPlugin.kt
  CaretSmoothPlugin.kt

app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/
  MermaidRenderer.kt
  MermaidWebViewPool.kt
  MermaidRenderCache.kt
  MermaidRenderModels.kt
  MermaidAssets.kt
  MermaidAssetManifest.kt

app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/
  MarkdownSecurityPolicy.kt
  MarkdownUrlPolicy.kt
  MarkdownElementPolicy.kt
  MarkdownUrlTransform.kt
  AllowedHtmlTags.kt
  LinkSafetyDialog.kt
```

最小接入点：

- `StreamingMessageStateManager.kt`：所有真实流式消息先生成 raw/display 双态。
- `ChatMessagesList.kt`：source-stripped、local、fallback 三类本地 render state 都走同一 pipeline。
- `ImageGenerationMessagesList.kt`：图像模式消息复用同一 render state。
- `BubbleContentTypes.kt`：气泡入口复用同一 render state。
- `ContentCoordinator.kt`：非直接块渲染路径使用 `StreamdownRenderInput`，禁止重复 repair。
- `TableAwareText.kt`：逐步变薄，保留兼容入口，接收 display text 但保留 raw copy/click 数据。
- `StreamBlocksRenderer.kt`：把解析函数迁出，渲染函数逐步迁入插件。
- `MarkdownRenderer.kt`：保留 fallback 和静态复杂内容渲染。

## 3.1 官方配置面映射

`StreamdownRenderConfig` 必须覆盖官方 Streamdown 配置面，避免只做主路径时漏掉边界能力：

| 官方配置 | Android 等效 |
| --- | --- |
| `children` | `StreamdownRenderInput.rawContent` |
| `mode` | `RenderMode.Streaming` / `RenderMode.Static` |
| `parseIncompleteMarkdown` | true 时启用 remend 修补，false 时直接显示半截 Markdown |
| `remend` | `StreamingRepairPolicy` |
| `isAnimating` | 当前消息是否处于流式输出中，控制 caret 和 smooth reveal |
| `className` | Compose `Modifier` 和样式 token，CSS 类名本身不适用 |
| `shikiTheme` | light/dark Shiki token 主题 |
| `components` | Compose slot override |
| `allowedTags` | `AllowedHtmlTags` |
| `plugins` | `StreamdownPluginRegistry`，管理 code、mermaid、math、cjk |
| `remarkPlugins` | Android AST transform hook，记录可支持子集 |
| `rehypePlugins` | Android HTML transform hook，记录可支持子集 |
| `allowedElements` / `disallowedElements` / `allowElement` / `unwrapDisallowed` / `skipHtml` | `MarkdownElementPolicy` |
| `urlTransform` | `MarkdownUrlTransform` |
| `caret` | `CaretStyle.Block` / `CaretStyle.Circle` |
| `controls` | `ControlsConfig`，支持 table、code、mermaid 粒度开关 |
| `mermaid` | Mermaid 主题、错误组件、超时、重试 |
| `linkSafety` | `LinkSafetyPlugin` 和确认弹窗 |
| `cdnUrl` | Android 不运行时加载 CDN，统一使用本地 assets 和 manifest |
| `BlockComponent` | block node 渲染 override |
| `parseMarkdownIntoBlocksFn` | `MarkdownBlockParserOverride` 测试 hook |
| `preprocess` | `StreamdownPreprocessor`，只作用于 display 前处理 |
| `defer` | 后台解析和低优先级节点升级 |
| `smooth` / `animated` | `SmoothRevealPolicy` 和动画回调 |
| `security` | `MarkdownSecurityPolicy` |
| `remarkRehypeOptions` | Android AST 转换选项，首版记录可支持子集 |
| `componentsByLanguage` | Compose language slot override |
| `icons` / `translations` | 控件图标和文案配置 |
| `dir` | auto/ltr/rtl 文本方向 |
| `literalTagContent` | 指定 HTML 标签内容按 literal text 处理 |

`ControlsConfig` 按官方结构落地：

- `true`：启用默认控件。
- `false`：关闭全部控件。
- `table`：支持 boolean 和 copy、download、fullscreen。
- `code`：支持 boolean 和 copy、download。
- `mermaid`：支持 boolean 和 download、copy、fullscreen、panZoom。

## 4. 数据流

### 流式阶段

```text
rawContent
-> StreamdownRenderPipeline.prepare(rawContent, isStreaming = true, policy)
-> StreamdownRenderInput(rawContent, displayContent, repairMeta)
-> MarkdownAstParser.parse(displayContent, mode = Streaming, rawMapping = repairMeta.mapping)
-> StreamdownRenderState(committedNodes, tailNodes, repairMeta)
-> StreamdownRenderer
-> Compose UI
```

要求：

- `rawContent` 不改。
- `displayContent` 不入库。
- 节点 range 记录 raw 和 repaired 的映射。
- stableId 优先使用 raw offset，避免补全文本改变 key。
- tail 只允许尾部重算。
- 每个入口只能调用一次 repair，`repairMeta.alreadyApplied` 用于防止重复补全。
- 复制、分享、下载、点击和图片加载只读取 raw range，不读取 synthetic suffix。

### 完成阶段

```text
rawContent
-> StreamdownRenderPipeline.prepare(rawContent, isStreaming = false, policy)
-> StreamdownRenderInput(rawContent, displayContent = rawContent, repairMeta = empty)
-> MarkdownAstParser.parse(rawContent, mode = Complete)
-> StreamdownRenderState(allNodes, repairMeta = empty)
-> StreamdownRenderer
-> Compose UI
```

要求：

- 完成态必须抛弃修补文本。
- 完成态失败才允许进入 Markwon fallback。
- 完成态和流式态切换不能导致整条消息重挂载。

### 灰度和双解析

```text
feature flag off
-> 旧路径显示
-> 新路径后台解析
-> StreamdownTelemetry 记录差异、耗时、fallback reason

feature flag on
-> 新路径显示
-> 旧路径保留为 fallback
-> telemetry 记录用户可见错误
```

`StreamdownFeatureFlags` 默认关闭，Debug 可通过设置页或开发开关启用。灰度完成前不得删除旧路径。

## 5. `remend` Android 等效设计

### 配置模型

```kotlin
data class StreamingRepairPolicy(
    val links: Boolean = true,
    val images: Boolean = true,
    val linkMode: LinkRepairMode = LinkRepairMode.Protocol,
    val bold: Boolean = true,
    val italic: Boolean = true,
    val boldItalic: Boolean = true,
    val inlineCode: Boolean = true,
    val strikethrough: Boolean = true,
    val katex: Boolean = true,
    val setextHeadings: Boolean = true,
    val comparisonOperators: Boolean = true,
    val htmlTags: Boolean = true,
    val handlers: List<StreamingRepairHandler> = emptyList(),
)
```

`LinkRepairMode.Protocol` 是默认值，对齐 Streamdown 的 `streamdown:incomplete-link` placeholder 行为。Android 点击策略必须把 placeholder 视为不可点击。`TextOnly` 保留为安全降级配置，半截链接只显示文本，闭合后再变链接。

### 扫描规则

`MarkdownDelimiterScanner` 使用单次线性扫描：

- 跳过反斜杠转义字符。
- 进入 fenced code 后暂停 inline 修补。
- 进入 inline code 后暂停强调和链接修补。
- 图片语法优先级高于链接。
- 数学语法优先级高于普通 `$` 文本。
- `<` 和 `>` 先按 comparison operator 保护，再进入 HTML tag 判断。
- 不完整 HTML tag 默认剥离显示，完成后再交给 HTML policy。
- 表格和 setext 标题按行扫描。

### 修补规则

| 语法 | 流式状态 | 显示处理 |
| --- | --- | --- |
| fenced code | opening fence 未闭合 | 末尾补同类 fence |
| inline code | 单反引号未闭合 | 末尾补反引号 |
| bold | `**` 或 `__` 未闭合 | 末尾补同类 delimiter |
| italic | `*` 或 `_` 未闭合 | 末尾补同类 delimiter |
| boldItalic | `***` 或 `___` 未闭合 | 末尾补同类 delimiter |
| strikethrough | `~~` 未闭合 | 末尾补 `~~` |
| math block | `$$` 或 `\[` 未闭合 | raw text 或补全，由 policy 控制 |
| math inline | `$` 或 `\(` 未闭合 | raw text |
| link | `[x](` 未闭合 | text-only |
| image | `![x](` 未闭合 | text-only |
| setext heading | 标题线未稳定 | 普通段落 |
| comparison operator | `1 < 2`、`a > b` | 保持普通文本 |
| html tag | `<div`、`<span class=` 未闭合 | 流式剥离或 literal text |

数学默认不强行补全。EveryTalk 已有 raw 状态，保留更稳。

## 6. AST 模型

Block 节点：

```kotlin
sealed interface MarkdownBlockNode {
    val stableId: String
    val range: MarkdownNodeRange

    data class Paragraph(...)
    data class Heading(...)
    data class BlockQuote(...)
    data class ListBlock(...)
    data class HorizontalRule(...)
    data class CodeBlock(...)
    data class Table(...)
    data class ImageBlock(...)
    data class MathBlock(...)
    data class MermaidBlock(...)
    data class HtmlBlock(...)
}
```

Inline 节点：

```kotlin
sealed interface MarkdownInlineNode {
    data class Text(...)
    data class Strong(...)
    data class Emphasis(...)
    data class Delete(...)
    data class InlineCode(...)
    data class Link(...)
    data class Image(...)
    data class InlineMath(...)
    data class HtmlSpan(...)
}
```

节点边界：

- Parser 不直接创建 Compose UI。
- Renderer 不重新解析 Markdown。
- Plugin 只处理自己声明支持的节点。
- Fallback 必须返回原因枚举。

## 7. Mermaid 设计

Android 没有天然 Mermaid renderer。要达到 Streamdown 效果，使用本地 WebView 沙箱。

### 组件

- `MermaidRenderer`：Compose 入口。
- `MermaidWebViewPool`：复用 WebView，避免每张图新建。
- `MermaidRenderCache`：按图源码、主题、尺寸缓存结果。
- `MermaidAssets`：从 assets 加载 `mermaid.min.js` 和渲染 HTML 模板。
- `MermaidAssetManifest`：记录 Mermaid 版本、来源 URL、许可证、SHA-256。

### 资产治理

- `mermaid.min.js` 必须来自官方 npm 包或 GitHub release。
- 资产提交时同步提交 `MermaidAssetManifest.kt` 和 `app1/app/src/main/assets/streamdown/mermaid.LICENSE.txt`。
- 构建或单测校验 SHA-256，不允许静默替换资产。
- 更新 Mermaid 必须单独提交，包含版本号、checksum 变化和 smoke 样例。
- 默认离线渲染，不允许运行时从 CDN 拉取脚本。

### 安全策略

- WebView 禁止 file access 访问任意路径。
- 使用 `WebViewAssetLoader` 加载固定本地 HTML，域名固定为应用私有 appassets 域。
- 禁止外部网络请求。
- Mermaid 源码通过 JSON 字符串注入，不拼接原始脚本。
- JS bridge 只暴露 `onRenderSuccess(svg)` 和 `onRenderError(message)` 两个回调。
- WebViewClient 拦截所有非本地导航。
- `allowFileAccess`、`allowContentAccess`、`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs` 全部关闭。
- `shouldInterceptRequest` 拦截非内置资产请求。
- SVG 返回后执行二次净化，禁止外链、脚本、事件属性和 `foreignObject`。
- 渲染超时后显示错误态。

### 用户能力

- 普通态：显示图。
- 错误态：显示源码和错误摘要。
- 控件：复制源码、复制 SVG、下载 PNG、全屏、缩放。

## 8. 代码高亮设计

现有 `SyntaxHighlighter` 覆盖多语言。要追平 Streamdown 的 Shiki 体验，分两层：

1. 流式层：继续用现有 Kotlin 高亮，长代码按阈值跳过高亮，保持流畅。
2. 完成层：增加 Shiki 等效高亮资产，使用本地 WebView 或预生成 TextMate 规则生成 token。

Shiki parity 的验收标准是 token 级快照接近官方主题。现有 `SyntaxHighlighter` 只能作为流式阶段和不支持语言的降级路径。若 Kotlin token 无法达到快照标准，完成态必须接入本地 Shiki JS 资产或 TextMate 规则引擎，并对资产做版本、许可证、SHA-256 管理。

## 9. CJK、caret 和 smooth reveal

CJK 插件负责中文体验，不应混在 parser 里：

- CJK 软换行归一只作用于 display text。
- 不在中文和英文之间盲目插入空格。
- 中文标点不触发错误的 list/table 判断。
- 与 inline code、math、link 的边界保持原文。
- `dir=auto/ltr/rtl` 通过 Compose 文本方向策略实现。

Caret 和 smooth reveal 只属于流式 UI 层：

- caret 不进入 raw/display content。
- smooth reveal 只影响尾部 tail node。
- 用户复制、分享、朗读时使用 raw content。
- 关闭动画或无障碍减少动态效果时，caret/smooth 必须自动禁用。

## 10. 链接安全设计

新增 `MarkdownUrlPolicy`：

- 允许 `http`、`https`、`mailto`、`tel`。
- 相对链接默认允许展示，点击前拦截。
- 支持 `allowedLinkPrefixes`、`allowedImagePrefixes`、`allowedProtocols`、`allowDataImages`、`defaultOrigin`。
- 支持 `urlTransform`，链接和图片路径在安全检查前完成归一。
- `javascript:`、`data:text/html`、未知 scheme 禁止点击。
- `data:image` 只允许图片渲染路径，不允许作为普通链接打开。

新增 `LinkSafetyDialog`：

- 显示目标域名和完整 URL。
- 可信域可直接打开。
- 非可信域需要用户确认。

新增 `MarkdownElementPolicy`：

- 支持 `allowedElements`、`disallowedElements`、`allowElement`。
- 支持 `unwrapDisallowed` 和 `skipHtml`。
- 支持 `literalTagContent`，例如 script/style 内容按纯文本或直接剥离。
- allowedTags 只允许声明过的 tag 和属性，事件属性永远禁止。

## 11. 性能设计

- 修补扫描 O(n)，只对尾部窗口重扫。
- AST 增量缓存复用 committed nodes。
- Mermaid 渲染放到异步队列。
- 代码高亮缓存复用 `HighlightCache`。
- 大消息启用 defer：先文本，再逐步升级块级节点。
- 每次 fallback 记录 `MarkdownAstFallbackReason`。

## 12. 兼容迁移

迁移顺序：

1. 新系统隐藏在 feature flag 后。
2. AI 消息双解析但只显示旧路径，记录差异。
3. 小范围消息启用新路径。
4. fallback 率达到门槛后设为默认。
5. 旧 `TableAwareText` 和 `StreamBlocksRenderer` 的解析职责迁移完成后再删减。
