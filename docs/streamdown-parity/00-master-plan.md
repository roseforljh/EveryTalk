# EveryTalk Android Streamdown Parity Implementation Plan

> **For Claude:** Use `C:/Users/33039/.codex/skills/executing-plans/SKILL.md` to implement this plan task-by-task.

**Goal:** 在 EveryTalk Android 端实现与 assistant-ui Streamdown 同级的流式 Markdown 渲染体验，覆盖流式补全、代码、数学、Mermaid、表格、链接安全、控件和性能稳定性。

**Architecture:** 保留现有 Compose 原生渲染主线，把 Streamdown 的能力拆成可测试的 Kotlin 层：流式修补、Markdown AST、插件式节点渲染、安全策略和性能缓存。最终内容永远以模型原文为准，流式修补只作用于显示层。

**Tech Stack:** Kotlin、Jetpack Compose、Markwon fallback、org.jetbrains:markdown、Room 无改动、JLatexMath、现有 SyntaxHighlighter、WebView 沙箱、JUnit4、Robolectric、Compose UI Test。

---

## 0. 文档位置

本专题文档统一放在 `docs/streamdown-parity/`，避免混在通用计划目录里难以查找：

- `docs/streamdown-parity/00-master-plan.md`：总体目标和范围。
- `docs/streamdown-parity/01-architecture.md`：架构、数据流、安全边界。
- `docs/streamdown-parity/02-implementation.md`：逐任务实施计划。
- `docs/streamdown-parity/03-verification.md`：验证矩阵和发布门槛。

## 1. 对齐目标

本计划对齐的是 Streamdown 的用户可见效果，不直接移植 React 包。官方能力来源：

- `@assistant-ui/react-streamdown`：流式 Markdown 渲染器。
- `remend`：流式阶段补全不完整 Markdown。
- 插件：code、math、mermaid、cjk。
- 配置：children、mode、parseIncompleteMarkdown、remend、isAnimating、className、shikiTheme、components、allowedTags、plugins、remarkPlugins、rehypePlugins、allowedElements、disallowedElements、allowElement、unwrapDisallowed、skipHtml、urlTransform、caret、controls、mermaid、linkSafety、cdnUrl、BlockComponent、parseMarkdownIntoBlocksFn。
- assistant-ui / Streamdown 站点扩展配置：preprocess、defer、smooth、animated、security、remarkRehypeOptions、componentsByLanguage、icons、translations、dir、literalTagContent。

EveryTalk Android 的目标是做出原生等效系统：

- 流式内容不断增长时，不出现大面积闪烁、错误 fallback、半截语法吞掉后文。
- 代码块、数学公式、表格、图片、链接、列表、引用在流式和完成后都稳定。
- Mermaid 图在 Android 内可渲染、可复制、可下载、可全屏查看。
- 链接安全和 HTML 白名单行为可控。
- 渲染耗时和重组数量可观测，有硬性回归门槛。

## 2. 范围

### 必做能力

| Streamdown 能力 | Android 对齐方式 | 目标 |
| --- | --- | --- |
| `remend.links` | Kotlin 状态机修补或降级链接 | 半截链接不破坏整段渲染 |
| `remend.images` | 半截图片降级文本，闭合后渲染图片 | 避免无效图片加载 |
| `remend.bold` | 补全 `**`、`__` | 粗体流式稳定 |
| `remend.italic` | 补全 `*`、`_` | 斜体流式稳定 |
| `remend.boldItalic` | 补全 `***`、`___` | 组合强调稳定 |
| `remend.inlineCode` | 补全单反引号 | 行内代码不吞文本 |
| `remend.strikethrough` | 补全 `~~` | 删除线稳定 |
| `remend.katex` | 数学 raw/渲染双态 | 半截公式不报错 |
| `remend.setextHeadings` | setext 标题延迟确认 | 未完成标题不抖动 |
| `remend.linkMode` | 默认 protocol placeholder，支持 text-only | 半截链接体验贴近 Streamdown |
| `remend.comparisonOperators` | `<`、`>` 比较符保护 | 避免流式阶段误判 HTML |
| `remend.htmlTags` | 不完整 HTML 标签剥离或降级文本 | 半截标签不污染 DOM/样式 |
| `remend.handlers` | Android 修补 handler 接口 | 支持后续自定义流式补全 |
| `parseIncompleteMarkdown` | 默认启用 remend，false 时禁用修补 | 支持原样显示半截 Markdown |
| code plugin | 现有 SyntaxHighlighter 加 Shiki 等效层 | 代码高亮和控件完整 |
| `plugins` | Android 插件注册表 | code、mermaid、math、cjk 可独立启停 |
| `remarkPlugins` / `rehypePlugins` | Android AST transform hook | 记录可支持子集，不直接运行 JS 插件 |
| `children` | `rawContent` 输入 | 对齐 React children 字符串 |
| `className` | Compose `Modifier` / 样式 token | Android 不保留 CSS 类名 |
| `isAnimating` | 流式中状态 | 控制 caret 和 smooth reveal |
| `shikiTheme` | light/dark token 主题 | 对齐官方 `github-light`、`github-dark` |
| math plugin | StableLatexRenderer 和 MathInline | 块级和行内公式稳定 |
| mermaid plugin | WebView 沙箱 MermaidRenderer | 图表渲染可交互 |
| cjk plugin | CJK 换行、标点和空格策略 | 中文排版稳定 |
| controls | Compose 控件层 | 复制、下载、全屏 |
| caret/smooth | 流式尾光标和节流动画 | 输出过程更接近 Web |
| linkSafety | Compose 确认弹窗 | 外链点击前检查 |
| security | URL、图片、协议、默认 origin 策略 | 阻断危险链接和图片 |
| allowedTags / element filtering | HTML 标签、属性、元素过滤 | 安全支持可控 HTML |
| urlTransform | 链接和图片 URL 归一或代理 | 与 Web 配置能力对齐 |
| components / BlockComponent | Android composable slot override | 支持替换块、代码头、语言渲染器 |
| icons / translations | 控件图标和文案配置 | 控件体验可本地化 |
| dir | auto/ltr/rtl 文本方向策略 | 支持双向文本和 CJK 混排 |
| `cdnUrl` | Android 禁用运行时 CDN | 统一使用本地 assets 和 manifest |

### 不改范围

- 不改变消息存储格式。
- 不修改真实模型输出文本。
- 不改 provider、网络协议、数据库。
- 不提交 git，除非用户明确要求。

## 3. 当前代码基线

EveryTalk 已经具备较强基础：

- `app1/app/src/main/java/com/android/everytalk/ui/components/content/EnhancedMarkdownText.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/coordinator/ContentCoordinator.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/table/TableAwareText.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlockParser.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamingRenderState.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkdownRenderer.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/syntax/SyntaxHighlighter.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/StableLatexRenderer.kt`

关键判断：现有工程已经在做 Android 原生 Markdown。Streamdown 对齐项目不应推翻它，应该把现有能力整理成更正式的 Streamdown Android 子系统。

## 4. 总体路线

### Phase 0：建立对齐基线

目标：先知道差距在哪里。

- 确认 `docs/streamdown-parity/` 被 git 跟踪，避免计划文档只存在本地。
- 建立 `StreamdownFeatureFlags`，默认关闭新渲染路径。
- 建立官方 prop 映射清单，每个 prop 必须标记 Android 等效、明确不适用或后续阶段。
- 建立入口盘点，覆盖 `StreamingMessageStateManager`、`StreamingRenderState`、`ContentCoordinator`、`TableAwareText`、`ChatMessagesList`、`ImageGenerationMessagesList`、`BubbleContentTypes`。
- 建立双解析 telemetry，旧路径显示，新路径只记录解析差异和 fallback 原因。
- 建立 `StreamdownParityCorpus`，收集官方示例和 EveryTalk 高风险样例。
- 建立单测矩阵，覆盖不完整 Markdown 每个 token 增长状态。
- 建立简单截图基线，覆盖聊天消息、代码块、表格、数学、Mermaid。
- 给现有 fallback 路径加原因枚举，记录哪些内容仍走 Markwon。

### Phase 1：实现 `remend` 等效层

目标：流式半截 Markdown 不再破坏渲染。

- 新增 `StreamingMarkdownRepairer`。
- 新增 `MarkdownDelimiterScanner`。
- 新增 `StreamingRepairPolicy`，对齐 Streamdown 的 remend 配置项。
- 修补只在 `isStreaming == true` 时启用。
- 完成后立即使用原文重新解析。

### Phase 2：正式 AST 和路由层

目标：把当前散落在 `StreamBlocksRenderer.kt` 和 `TableAwareText.kt` 的解析逻辑收束成一个域模型。

- 新增 `markdown/ast` 包。
- 统一 block 节点、inline 节点、范围、stableId。
- 解析层优先复用现有逻辑，逐步接入 `org.jetbrains:markdown`。
- `MarkdownRenderer` 变成 fallback，不再是 AI 正文主路径。

### Phase 3：插件式渲染器

目标：做到 Streamdown 插件能力在 Android 端可组合。

- `CodeMarkdownPlugin`：代码块、行内代码、复制、预览、下载。
- `ShikiParityPlugin`：完成态必须对齐 Shiki token、主题和语言覆盖，现有 Kotlin 高亮只能作为流式降级。
- `MathMarkdownPlugin`：行内数学、块级数学、raw 状态、错误状态。
- `MermaidMarkdownPlugin`：资产治理、WebView 沙箱渲染、全屏、复制、下载。
- `CjkMarkdownPlugin`：中文换行、标点、软换行归一。
- `LinkSafetyPlugin`：外链确认、scheme 白名单、可信域判断。
- `CaretSmoothPlugin`：流式尾部 caret、smooth reveal 和节流。
- `HtmlSecurityPlugin`：allowedTags、元素过滤、literal tag 和 HTML indentation。
- `UrlTransformPlugin`：链接和图片 URL transform、默认 origin、blocked 状态。
- `AndroidSlotPlugin`：components、componentsByLanguage、BlockComponent、icons、translations 的 Compose 等效能力。

### Phase 4：控件、动画和性能

目标：用户看到的细节接近 Streamdown。

- 代码块控件统一化。
- 表格控件加入复制和导出。
- Mermaid 控件加入复制、下载、全屏、缩放。
- 流式尾部 caret。
- smooth reveal 节流。
- 后台解析和增量缓存。

### Phase 5：硬核验证和灰度

目标：上线前能证明等效，不靠主观感觉。

- Golden corpus 单测全绿。
- Compose UI 截图对比通过。
- `:app:compileDebugKotlin` 必跑。
- 涉及 UI 后 `:app:assembleDebug` 必跑。
- fallback 触发率低于门槛。
- 大消息性能不退化。

## 5. 成功标准

### 功能标准

- 100 个以上 Markdown 流式样例在每个增量前缀都可渲染。
- Streamdown 官方 props 都有 Android 等效策略、明确不适用记录或测试覆盖。
- Streamdown remend 官方配置项都有 Android 等效策略，包括 `comparisonOperators`、`htmlTags` 和 `handlers`。
- 代码、数学、Mermaid、表格、图片、链接都有流式和完成态测试。
- 每个 fallback 都有可解释原因。

### 体验标准

- 流式输出时布局不大跳。
- 未闭合代码块不会吞掉后续普通文本。
- 未闭合数学公式显示 raw text，闭合后切换为公式。
- 半截图片不会触发图片加载。
- Mermaid 渲染失败时有稳定错误态。
- 半截 HTML 标签和比较符不会破坏后续显示。
- 复制、下载、点击、图片加载永远使用 raw content，不使用 synthetic display content。

### 性能标准

- 5 万字符消息能完成解析和渲染。
- 增量解析只重算尾部。
- 代码高亮和 Mermaid 渲染不阻塞主线程。
- LazyColumn 回收后缓存可复用。

## 6. 文档拆分

- 架构计划：`docs/streamdown-parity/01-architecture.md`
- 实施计划：`docs/streamdown-parity/02-implementation.md`
- 验证计划：`docs/streamdown-parity/03-verification.md`
