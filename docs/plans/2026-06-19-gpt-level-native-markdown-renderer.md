# GPT 级原生 Markdown 渲染迁移计划

## 目标

把 AI 正文 Markdown 主路径迁移为“解析成结构化节点，再按节点交给 Compose 渲染器分发”的架构。Markwon 保留为临时 fallback，只处理尚未迁移的复杂节点。

## 已确认代码证据

- GPT Android 在 `fuck-gpt/jadx-out/sources/defpackage/v950.java:131` 将 `sx2` AST 交给 `j440.a(...)`。
- GPT Android 在 `fuck-gpt/jadx-out/sources/defpackage/j440.java:391` 进入 `f440.a(...)`。
- GPT Android 在 `fuck-gpt/jadx-out/sources/defpackage/f440.java:104/110/114/125/133/159/165` 按列表、分割线、标题、代码块、文本/段落、表格节点分发。
- GPT Android 在 `fuck-gpt/jadx-out/sources/defpackage/f440.java:77` 将引用块外观路径交给 `ij4.a(...)`，该路径读取 `RichTextStyle.blockQuoteGutter`。
- GPT Android 在 `fuck-gpt/jadx-out/sources/defpackage/ij4.java:131-134` 先绘制引用块 gutter，再调用 `k440.a(..., gx9Var, ...)` 渲染传入的子内容 composable。
- EveryTalk 当前在 `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkdownRenderer.kt:1478/1483` 仍调用 `markwon.parse(...)` 与 `markwon.render(...)`。
- EveryTalk 已有原生 Compose 渲染入口在 `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt` 的 `NativeMarkdownBlocksSegment(...)`。

## 当前迁移状态

- 已完成：简单段落、行内粗体/斜体/删除线/代码、普通 Markdown 链接、引用链接、自动链接、HTML anchor 链接、常见 HTML entity、`&ndash;` / `&mdash;` / `&hellip;` / `&copy;` / `&reg;` / `&trade;`、`&ldquo;` / `&rdquo;` / `&minus;` / `&times;` / `&divide;` / `&rarr;` / `&larr;`、常见希腊字母命名实体、HTML 换行标签、简单内联 HTML 标签、带属性的简单内联 HTML 标签、标题、分割线、引用、引用块内部子 block 递归渲染、普通列表、嵌套列表、列表项空行 continuation 子 block 递归渲染并保留交互回调、围栏代码块、HTML pre code、Markdown 表格、表格内普通图片、表格内行内数学、独占一行 Markdown 图片、独占一行图片引用链接、段落内普通图片、段落内行内数学、已闭合块级数学公式可以进入原生 Compose 路径。
- 已完成：`StreamingMarkdownRenderPath.ComposeBlockMarkdown` 作为原生块级 Markdown 路由，`FullMarkdown` 只表示需要完整 fallback 的复杂内容。
- 保留 fallback：非 http/https 或无法安全归一化的 HTML anchor、未列入归一化规则的 HTML 标签、仍未列入白名单的少见 HTML entity、无法解析的复杂图片 Markdown。未闭合或失败的数学状态在 stream token 路径保持原文渲染，不交给 Markwon fallback。

## 阶段计划

### Phase 1：扩大原生块级节点覆盖

- 代码块：已把 Markdown fenced code 和 HTML pre code 接入原生 CodeBlock 节点；主 native Markdown 代码块已切到 GPT `wr7` 风格轻量 renderer。
- 表格：已把 Markdown 表格接入原生 Table 节点，并直接分发到 `TableRenderer`。
- 图片和链接：普通 Markdown 链接、引用链接和自动链接已进入原生 inline token；独占一行 `![alt](url)`、`![alt](url "title")`、`![alt][id]` 加定义的图片已进入原生 Image block；段落内普通图片会拆成段落、Image block、段落；表格内独占图片和图文混排图片已进入 `TableRenderer` 原生渲染。

### Phase 2：抽出正式 Markdown AST/domain model

- 新增独立 domain model：Paragraph、Heading、List、BlockQuote、HorizontalRule、CodeBlock、Table、Math、Image、Link、InlineText。
- 解析层从 `StreamBlocksRenderer.kt` 拆出到独立 parser 文件，避免 UI 文件继续膨胀。
- 优先复用现有稳定 parser 逻辑，后续再评估 `org.jetbrains:markdown` AST 接入。

### Phase 3：替换 AI 正文主路径

- `ContentCoordinator` 对 AI 消息优先尝试原生 AST 渲染。
- `TableAwareText` 逐步降级为兼容层，最终只处理表格专用交互或旧 fallback。
- `MarkdownRenderer` 保留 fallback 日志，记录触发原因和节点类型。

### Phase 4：按 GPT 证据继续补视觉 token

- 标题字号、字重、H2/H4/H5 alpha、H3 italic 已追到 `v150.d(...)`、`gzf.java`、`a5i.java` 并落地。
- 默认 paragraphSpacing 已追到 `mmu.java` 和 `pke.java` 并落地：普通段落后间距和 heading 前间距使用 `8dp`，避免旧的 `12dp / 16dp` 节奏造成正文过松。
- 分割线厚度、颜色和间距已追到 `tp40.b(...)`、`mvj.java`、`mmu.java` 并落地。
- 普通 Markdown 代码块默认容器、字体、背景和 padding 已追到 `wr7.a(...)`、`mmu.java`、`or7.smali` 并落地；EveryTalk 专用代码预览卡仍保留在非 native/fallback 场景。
- 顶层无序列表 marker 列宽已追到 `zji.java`、`yji.smali`、`xji.smali` 并按 `markerIndent + marker自身宽度 + contentsIndent` 落地。
- 有序列表动态 marker 列宽已追到 `zji.java`、`yji.smali`、`xji.smali` 并按 `markerIndent + marker文本宽度 + contentsIndent` 落地。
- Ordered marker 样式轮换已追到 `i95.java`、`qm2.java`、`bfh.java` 并按层级取模 4 落地。
- 嵌套列表缩进已追到 `zji.smali`、`wji.java`、`yji.smali`、`xji.smali`，EveryTalk native Compose 列表已按 GPT 递归 marker/content 两列布局改为累计祖先 marker 列宽。
- 列表项 content composable 已追到 `nbc.java`、`wji.java`、`yji.smali`、`xji.smali` 并落地：空行后 continuation 保存为 `NativeStreamingListItem.children`，正文列递归调用 `NativeMarkdownBlocksSegment(...)`，并保留 `isStreaming`、代码预览、复制、图片点击回调。
- 引用块默认 gutter 已追到 `f440.java`、`lmu.java`、`mmu.java`、`ij4.java`、`gj4.java`、`ua.java` 并落地：左外距 `6dp`、竖条宽 `3dp`、右外距 `6dp`、内容上下 padding `4dp`、竖条正文色 `0.25` alpha、无整块背景面板。
- 引用块内部子内容 composable 已追到 `ij4.java:131-134` 并落地：`NativeStreamingMarkdownBlock.children` 保存递归解析结果，`NativeBlockQuote(...)` 对非空 `children` 继续调用 `NativeMarkdownBlocksSegment(...)`，`hashNativeBlocks(...)` 已把 `children.hashCode()` 纳入缓存 hash。
- 表格 `cellPadding = 8`、`borderStrokeWidth = 1dp`、默认等宽列布局和 `Lzn9` 专属横向滚动分支已追到 `ny40.d(...)`、`qjy.java`、`ijy.smali`、`hm00.smali`、`mmu.java`、`zao.java` 并落地。
- 表格 `dividerStyle = k850.s0` 已追到 `zao.java`、`etf.smali`、`hjy.smali` 并落地为表头底线、body 内部横线、无竖线。颜色 token 仍使用 EveryTalk 本地 Material outline 映射。

## 验证门槛

- 每次新增节点必须先写单测确认路由进入原生路径。
- 每次新增 Compose 渲染节点必须跑对应单测和 `:app:compileDebugKotlin`。
- 修改 Compose UI、资源或 Manifest 时必须跑 `:app:assembleDebug`。
- fallback 数量下降必须有日志或测试证明，不能只靠主观观感。
