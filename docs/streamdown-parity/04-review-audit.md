# Android Streamdown Parity Plan Review Audit

## 审查结论

修复后计划可以作为 EveryTalk Android 对齐 assistant-ui Streamdown 的实施基线。它采用现有 Compose 原生渲染链路增量演进，保留 feature flag、旧路径 fallback、raw/display 双态和灰度 telemetry，方向正确。

## 审查依据

- assistant-ui Streamdown 文档：`https://www.assistant-ui.com/docs/ui/streamdown`
- Streamdown 配置文档：`https://streamdown.ai/docs/configuration`
- EveryTalk 现有入口：`StreamingMessageStateManager`、`ChatMessagesList`、`ImageGenerationMessagesList`、`BubbleContentTypes`、`EnhancedMarkdownText`、`ContentCoordinator`、`TableAwareText`、`StreamBlocksRenderer`、`MarkdownRenderer`

## 已修复缺口

1. 入口命名修正：入口枚举改为 `StreamingMessageStateManager`，与实际源码一致。
2. 官方 prop 覆盖补齐：新增 children、mode、parseIncompleteMarkdown、remend、isAnimating、className、shikiTheme、components、allowedTags、plugins、remarkPlugins、rehypePlugins、allowedElements、disallowedElements、allowElement、unwrapDisallowed、skipHtml、urlTransform、caret、controls、mermaid、linkSafety、cdnUrl、BlockComponent、parseMarkdownIntoBlocksFn 的 Android 映射。
3. remend 覆盖补齐：新增 `comparisonOperators`、`htmlTags`、`handlers`。
4. raw/display 边界补强：复制、分享、下载、点击、图片加载和表格导出必须使用 raw range，不允许使用 synthetic display content。
5. HTML 和 URL 安全补强：新增 allowed/disallowed/allowElement、unwrap、skipHtml、literal tag、URL transform、prefix、protocol、defaultOrigin 的计划和测试。
6. Mermaid 安全补强：要求 WebViewAssetLoader、关闭 file/content access、拦截外部请求、SVG 二次净化、资产 manifest、license 和 SHA-256。
7. Shiki parity 补强：现有 Kotlin 高亮只能作为流式降级，完成态必须通过 token 快照对齐官方 Shiki 主题，不允许名义对齐。
8. 控件补强：补齐 table fullscreen、controls 粒度开关、icons、translations。
9. CJK 和方向补强：补齐 `dir=auto/ltr/rtl`。
10. 验证补强：新增配置映射测试、HTML policy 测试、URL transform 测试、WebView 安全测试、Shiki token 快照、raw 内容完整性和重复 repair 测试。
11. 语义修正：`parseIncompleteMarkdown=true` 表示启用 remend 修补，`false` 才禁用不完整 Markdown 处理。
12. 资产策略修正：`cdnUrl` 在 Android 端只作为配置兼容项，运行时仍禁止从 CDN 拉取 Mermaid 或 Shiki 资产。
13. 默认值修正：`linkMode` 默认改为 `Protocol`，并要求 `streamdown:incomplete-link` placeholder 不可点击。

## 仍需实施时重点复核

1. 新增类数量较多，落地时每个类必须先确认现有代码是否已有可复用实现。
2. Shiki 完成态若接入 JS 或 TextMate 引擎，必须单独做资产治理和性能评估。
3. Mermaid WebView 需要真机或 instrumentation 覆盖，Robolectric 不能证明真实渲染。
4. `org.jetbrains:markdown` 与现有 parser 迁移时必须保持旧测试全绿，不能一次性删除旧路径。

## NOT in scope

- 不改 provider、网络协议、数据库和消息存储格式。
- 不把 React Streamdown 或 Node runtime 直接塞进 Android 主渲染链路。
- 不在灰度完成前删除 `MarkdownRenderer`、`TableAwareText` 或旧 streaming 解析路径。
- 不提交 git，除非用户明确要求。

## What already exists

- `StreamingMessageStateManager` 已负责流式消息状态，应作为 raw/display 入口。
- `StreamingRenderState` 已有 committed/tail 思路，应复用为增量渲染基础。
- `StreamBlocksRenderer` 和 `TableAwareText` 已有原生 block、table、code、math 路由，应先抽薄再替换。
- `CodeBlockCard` 和 `SyntaxHighlighter` 已有代码块控件与轻量高亮，应作为流式降级。
- `StableLatexRenderer` 和 `MathStreamingPolicy` 已有数学渲染策略，应纳入 Math plugin。
- `MarkdownRenderer` 已有 Markwon fallback，应保留为复杂静态内容和故障回退。

## 失败模式核对

| 新路径 | 生产失败模式 | 计划覆盖 |
| --- | --- | --- |
| repair | synthetic suffix 被复制或点击 | raw/display range 测试和完整性测试 |
| AST parse | 半截 HTML 或 `<` 比较符误判 | comparison/htmlTags 测试 |
| Mermaid | WebView 请求外网或返回危险 SVG | WebView 设置、请求拦截、SVG 净化测试 |
| Shiki | 完成态高亮与官方差距大 | token 快照测试和资产治理 |
| Link | 危险 URL 被打开 | URL policy、link safety、urlTransform 测试 |
| Image | 半截图片 URL 触发加载 | image raw URL 和前缀测试 |
| CJK | 中文标点误触发表格或标题 | CJK corpus 和 dir 测试 |
| Smooth | tail 动画导致 committed nodes 重挂载 | committed/tail 稳定性测试 |

## 最终判断

计划经过本轮修复后，覆盖范围、顺序、安全边界和验证门槛完整。实施时应按 `02-implementation.md` 的任务顺序推进，每个任务先测试、再最小实现、再运行对应验证。
