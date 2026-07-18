# Android Streamdown Parity Implementation Plan

## 目标

按可验证的小步把 EveryTalk Android 渲染升级为 Streamdown 等效系统。每个任务先写测试，再写最小实现，再跑相关验证。

## 执行规则

- 所有 Gradle 命令在 `app1/` 下执行。
- 修改 Kotlin 逻辑后至少跑相关单测和 `:app:compileDebugKotlin`。
- 修改 Compose UI 后至少跑 `:app:assembleDebug`。
- 不修改 `local.properties`、签名文件、密钥。
- 不提交 git，除非用户明确要求。
- 现有脏文件先不碰，落地时先确认差异。
- 本专题文档固定放在 `docs/streamdown-parity/`。
- 总验证过滤统一使用 `--tests "*Streamdown*"`，避免大小写不一致漏跑。
- 每个官方 Streamdown prop 必须在 `StreamdownRenderConfigParityTest` 中有等效、明确不适用或后续阶段记录。

## Task 0：文档跟踪、开关和入口盘点

**Files:**

- Modify: `.gitignore`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownFeatureFlags.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownEntryPoint.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownTelemetry.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderConfig.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownFeatureFlagsTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownEntryPointTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderConfigParityTest.kt`

**Step 1: Write the failing tests**

覆盖：

- `docs/streamdown-parity/` 不被 `.gitignore` 忽略。
- `StreamdownFeatureFlags.enabled` 默认 `false`。
- 入口枚举包含 `StreamingMessageStateManager`、`ChatMessagesList`、`ContentCoordinator`、`TableAwareText`、`ImageGenerationMessagesList`、`BubbleContentTypes`。
- `StreamdownRenderConfig` 覆盖官方 props：children、mode、parseIncompleteMarkdown、remend、isAnimating、className、shikiTheme、components、allowedTags、plugins、remarkPlugins、rehypePlugins、allowedElements、disallowedElements、allowElement、unwrapDisallowed、skipHtml、urlTransform、caret、controls、mermaid、linkSafety、cdnUrl、BlockComponent、parseMarkdownIntoBlocksFn。
- `StreamdownRenderConfig` 覆盖站点扩展配置：preprocess、defer、smooth、animated、security、remarkRehypeOptions、componentsByLanguage、icons、translations、dir、literalTagContent。
- telemetry 可以记录 entry point、raw length、display length、fallback reason。

**Step 2: Run test to verify it fails**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownFeatureFlagsTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownEntryPointTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownRenderConfigParityTest*"
```

Expected：FAIL，提示类或行为不存在。

**Step 3: Write minimal implementation**

新增：

```kotlin
enum class StreamdownEntryPoint {
    StreamingMessageStateManager,
    ChatMessagesList,
    ContentCoordinator,
    TableAwareText,
    ImageGenerationMessagesList,
    BubbleContentTypes,
}

object StreamdownFeatureFlags {
    val enabled: Boolean = false
    val doubleParseTelemetry: Boolean = true
}
```

`StreamdownTelemetry` 先只提供 debug no-op sink 和可测的 event data class。
`StreamdownRenderConfig` 首版可只保存配置值和默认值，不在 UI 中立即启用全部能力。

**Step 4: Run tests to verify they pass**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownFeatureFlagsTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownEntryPointTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownRenderConfigParityTest*"
```

Expected：PASS。

## Task 1：建立 Streamdown parity corpus

**Files:**

- Create: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownParityCorpus.kt`
- Create: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownParityCorpusTest.kt`

**Step 1: Write failing test**

覆盖样例分类：

- `**bold`
- `*italic`
- `***bold italic`
- `` `inline code ``
- `~~delete`
- `[label](https://example.com`
- `![alt](https://example.com/a.png`
- `$$ x^2`
- `\(`、`\[`
- 未闭合 fenced code
- setext heading 逐 token
- comparison operator：`1 < 2`、`a > b`
- 不完整 HTML：`<div`、`<span class=`
- custom remend handler 顺序
- Markdown 表格逐行
- Mermaid fenced block

Expected：测试先失败，因为 corpus 和 repairer 不存在。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownParityCorpusTest*"
```

**Step 3: Minimal implementation**

只创建 corpus 数据结构，不接生产代码：

```kotlin
data class StreamdownParityCase(
    val name: String,
    val finalMarkdown: String,
    val checkpoints: List<String> = finalMarkdown.indices.map { finalMarkdown.take(it + 1) },
)
```

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownParityCorpusTest*"
```

Expected：PASS。

## Task 2：新增流式修补策略模型

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/StreamingRepairPolicy.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/StreamingRepairResult.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/StreamingRepairHandler.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/repair/StreamingRepairPolicyTest.kt`

**Step 1: Write failing test**

验证默认值对齐 Streamdown remend：

- links true
- images true
- bold true
- italic true
- boldItalic true
- inlineCode true
- strikethrough true
- katex true
- setextHeadings true
- linkMode 默认 `Protocol`
- comparisonOperators true
- htmlTags true
- handlers 默认为空且按顺序执行

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamingRepairPolicyTest*"
```

**Step 3: Minimal implementation**

新增 enum 和 data class：

```kotlin
enum class LinkRepairMode { Protocol, TextOnly }

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

`StreamingRepairResult` 包含：

- `raw`
- `repaired`
- `appliedFixes`
- `hasSyntheticSuffix`
- `rawToDisplayRanges`
- `fallbackReason`

**Step 4: Verify**

同上，Expected：PASS。

## Task 3：实现 Markdown delimiter scanner

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/MarkdownDelimiterScanner.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/repair/MarkdownDelimiterScannerTest.kt`

**Step 1: Write failing tests**

覆盖：

- 转义字符跳过。
- fenced code 内不扫描强调。
- inline code 内不扫描链接。
- 图片优先于链接。
- 数学 delimiter 不当作普通 `$`。
- comparison operator 不当作 HTML tag。
- 不完整 HTML tag 可识别并记录。
- 只返回尾部未闭合 delimiter。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownDelimiterScannerTest*"
```

**Step 3: Minimal implementation**

实现单次线性扫描：

- `ScanMode.Normal`
- `ScanMode.FencedCode`
- `ScanMode.InlineCode`
- `ScanMode.LinkLabel`
- `ScanMode.LinkDestination`
- `ScanMode.Math`

不做 UI，不依赖 Compose。

**Step 4: Verify**

同上，Expected：PASS。

## Task 4：实现 StreamingMarkdownRepairer

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/repair/StreamingMarkdownRepairer.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/repair/StreamingMarkdownRepairerTest.kt`

**Step 1: Write failing tests**

断言：

- `**abc` -> `**abc**`
- `***abc` -> `***abc***`
- `` `abc `` -> `` `abc` ``
- `~~abc` -> `~~abc~~`
- 未闭合 fenced code 末尾补同类 fence。
- 半截 link 使用 text-only，不产生 clickable link。
- 默认半截 link 使用 `streamdown:incomplete-link` placeholder，且不可点击。
- `linkMode = TextOnly` 时半截 link 只显示 label。
- 半截 image 不产生 image node。
- 数学未闭合时保持 raw 状态。
- `1 < 2` 保持普通文本。
- `<div` 流式显示不产生 HTML node。
- custom handler 可补自定义 token 且不会重复补。
- `parseIncompleteMarkdown = true` 时启用 repair，`false` 时跳过 repair 并显示半截 Markdown。
- `isStreaming = false` 时返回原文。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamingMarkdownRepairerTest*"
```

**Step 3: Minimal implementation**

只做显示层字符串修补和 metadata：

```kotlin
object StreamingMarkdownRepairer {
    fun repair(
        text: String,
        isStreaming: Boolean,
        policy: StreamingRepairPolicy = StreamingRepairPolicy(),
    ): StreamingRepairResult
}
```

**Step 4: Verify**

同上，Expected：PASS。

## Task 5：统一接入现有流式状态构建和显示入口

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderInput.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderPipeline.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamingRenderState.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/statecontroller/StreamingMessageStateManager.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/content/EnhancedMarkdownText.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/coordinator/ContentCoordinator.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/table/TableAwareText.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streaming/StreamBlockParserTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/repair/StreamingMarkdownRepairerIntegrationTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownEntryPointCoverageTest.kt`

**Step 1: Write failing test**

验证：

- `buildStreamingRenderState(..., isStreaming = true)` 使用 repaired content。
- `buildStreamingRenderState(..., isComplete = true)` 使用 raw content。
- stableId 不因 synthetic suffix 大范围变化。
- 每个入口都通过 `StreamdownRenderPipeline.prepare(...)`，没有重复 repair。
- `ChatMessagesList` 的 source-stripped、local、fallback render state 都保留 raw/display 分离。
- `EnhancedMarkdownText -> ContentCoordinator -> TableAwareText` 路径不会再次修补已经修补过的 display text。
- 复制、分享、下载、链接点击、图片加载和表格导出使用 raw range，不能包含 synthetic 内容。

**Step 2: Run failing tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamingMarkdownRepairerIntegrationTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownEntryPointCoverageTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamBlockParserTest*"
```

**Step 3: Minimal implementation**

给 `StreamingRenderState` 增加：

- `rawContent`
- `displayContent`
- `repairFixes`
- `repairAlreadyApplied`

新增统一入口：

```kotlin
object StreamdownRenderPipeline {
    fun prepare(
        rawContent: String,
        isStreaming: Boolean,
        policy: StreamingRepairPolicy = StreamingRepairPolicy(),
        entryPoint: StreamdownEntryPoint,
        alreadyRepaired: Boolean = false,
    ): StreamdownRenderInput
}
```

所有生产入口只允许调用这个 pipeline。Composable 可以继续接收 `String`，但必须明确传入 raw/display 对应字段，复制、点击、下载使用 raw 字段。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamingMarkdownRepairerIntegrationTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamdownEntryPointCoverageTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamBlockParserTest*"
```

## Task 6：抽出正式 AST 模型

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownBlockNode.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownInlineNode.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownNodeRange.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownStableId.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownNodeModelTest.kt`

**Step 1: Write failing test**

验证：

- range 可表达 raw 和 repaired 范围。
- stableId 基于 messageId、node type、raw start。
- 节点 hash 不包含 UI 对象。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownNodeModelTest*"
```

**Step 3: Minimal implementation**

只建模型，不接渲染。

**Step 4: Verify**

Expected：PASS。

## Task 7：把 native parser 迁出 UI 文件

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownAstParser.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streaming/StreamBlocksRendererRoutingTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/ast/MarkdownAstParserTest.kt`

**Step 1: Write failing test**

把当前 `parseNativeStreamingMarkdownBlocks` 的高价值场景复制到 `MarkdownAstParserTest`。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownAstParserTest*"
```

**Step 3: Minimal implementation**

迁移解析逻辑，旧函数保留薄包装：

```kotlin
internal fun parseNativeStreamingMarkdownBlocks(...) =
    MarkdownAstParser.parseBlocks(...).toLegacyNativeBlocks()
```

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownAstParserTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamBlocksRendererRoutingTest*"
```

## Task 8：新增 StreamdownRenderer 入口

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderer.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownRenderConfig.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownAndroidSlots.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/coordinator/ContentCoordinator.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownRendererRoutingTest.kt`

**Step 1: Write failing test**

用源码文本测试路由：

- AI 消息走 `StreamdownRenderer`。
- User 消息保持现有宽度和长按行为。
- fallback 保留。
- `componentsByLanguage` 可以覆盖 mermaid/code 语言渲染。
- `BlockComponent` override 不破坏 raw/display 映射。
- `plugins` 可以独立启停 code、mermaid、math、cjk。
- `remarkPlugins`、`rehypePlugins` 作为 Android transform hook 记录支持子集。
- `className` 映射到 Compose style token，不传递 CSS 字符串。
- `cdnUrl` 默认禁用运行时 CDN，Mermaid 和 Shiki 资产只从本地 manifest 加载。

**Step 2: Run failing test**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownRendererRoutingTest*"
```

**Step 3: Minimal implementation**

`StreamdownRenderer` 先代理到现有 `NativeMarkdownBlocksSegment`。

**Step 4: Verify**

同上。

## Task 9：代码插件对齐

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/MarkdownPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/CodeMarkdownPlugin.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/CodeMarkdownPluginTest.kt`

**Step 1: Write failing tests**

覆盖：

- 流式 code 长度超过阈值时可跳过高亮。
- 完成态执行高亮。
- 复制按钮使用原始 code。
- 下载内容不含 synthetic fence。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*CodeMarkdownPluginTest*"
```

**Step 3: Minimal implementation**

先复用现有 `CodeBlockCard` 和 `SyntaxHighlighter`。

**Step 4: Verify**

同上，再跑：

```bash
cd app1
./gradlew :app:compileDebugKotlin
```

## Task 10：数学插件对齐

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/MathMarkdownPlugin.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/MathStreamingPolicy.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/MathMarkdownPluginTest.kt`

**Step 1: Write failing tests**

覆盖：

- 未闭合 `$` raw text。
- 未闭合 `$$` raw text。
- `\(` 闭合后变 inline math。
- `\[` 闭合后变 block math。
- 金额 `$20` 不触发数学。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MathMarkdownPluginTest*"
```

**Step 3: Minimal implementation**

复用 `StableLatexRenderer`、`MathInline` 和现有策略。

**Step 4: Verify**

同上。

## Task 11：Mermaid 资产治理与插件

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/MermaidMarkdownPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidRenderer.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidWebViewPool.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidRenderCache.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidAssetManifest.kt`
- Create: `app1/app/src/main/assets/streamdown/mermaid-renderer.html`
- Create: `app1/app/src/main/assets/streamdown/mermaid.min.js`
- Create: `app1/app/src/main/assets/streamdown/mermaid.LICENSE.txt`
- Create: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidAssetManifestTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/MermaidMarkdownPluginTest.kt`

**Step 1: Write failing tests**

覆盖：

- `MermaidAssetManifest` 包含 version、sourceUrl、license、sha256。
- assets 中的 `mermaid.min.js` 实际 SHA-256 等于 manifest。
- assets 中存在 license 文本。
- fenced code language 为 `mermaid` 时生成 Mermaid node。
- 非 mermaid code 仍走 Code plugin。
- Mermaid 源码 stable hash 一致。
- 失败状态可序列化。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MermaidAssetManifestTest*"
./gradlew :app:testDebugUnitTest --tests "*MermaidMarkdownPluginTest*"
```

**Step 3: Minimal implementation**

先完成资产 manifest、checksum 校验、Mermaid fence 识别和错误态 UI。`mermaid.min.js` 必须来自官方 npm 包或 GitHub release，版本和 checksum 写入 `MermaidAssetManifest.kt`。

**Step 4: WebView rendering**

新增 WebView 组件：

- 禁用任意外部导航。
- 注入源码使用 JSON。
- JS bridge 只暴露 `onRenderSuccess(svg)` 和 `onRenderError(message)`。
- WebViewClient 拦截所有非本地 URL。
- 禁止运行时从 CDN 拉取脚本。
- 渲染成功返回 SVG。
- 渲染失败返回错误。
- 超时显示错误态。

**Step 5: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MermaidAssetManifestTest*"
./gradlew :app:testDebugUnitTest --tests "*MermaidMarkdownPluginTest*"
./gradlew :app:assembleDebug
```

## Task 12：安全、HTML 和 URL transform 插件

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/MarkdownUrlPolicy.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/MarkdownSecurityPolicy.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/MarkdownElementPolicy.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/MarkdownUrlTransform.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/AllowedHtmlTags.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/security/LinkSafetyDialog.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/LinkSafetyPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/HtmlSecurityPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/UrlTransformPlugin.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/security/MarkdownUrlPolicyTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/security/MarkdownElementPolicyTest.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/security/MarkdownUrlTransformTest.kt`

**Step 1: Write failing tests**

覆盖：

- `https` 允许。
- `http` 可配置允许。
- `mailto`、`tel` 允许。
- `javascript` 禁止。
- `data:text/html` 禁止。
- `data:image` 只能走图片路径。
- 半截 URL 不可点击。
- `allowedLinkPrefixes`、`allowedImagePrefixes`、`allowedProtocols` 生效。
- `defaultOrigin` 正确解析相对链接。
- `urlTransform` 先归一，再进入安全检查。
- `allowedElements`、`disallowedElements`、`allowElement` 生效。
- `unwrapDisallowed` 保留安全子文本。
- `skipHtml` 完全跳过 HTML。
- `literalTagContent` 不执行也不解析内部内容。
- 事件属性和未知 style 永远禁止。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownUrlPolicyTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownElementPolicyTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownUrlTransformTest*"
```

**Step 3: Minimal implementation**

先完成 policy，再接 Compose 弹窗。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*MarkdownUrlPolicyTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownElementPolicyTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownUrlTransformTest*"
./gradlew :app:assembleDebug
```

## Task 13：控件层

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/TableControlsPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/AndroidSlotPlugin.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/table/TableRenderer.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/mermaid/MermaidRenderer.kt`

**Step 1: Write tests**

能纯函数测试的控件先测：

- 表格转 TSV。
- 表格转 Markdown。
- 表格全屏开关。
- table controls 支持 boolean 和 copy、download、fullscreen。
- code controls 支持 boolean 和 copy、download。
- mermaid controls 支持 boolean 和 copy、download、fullscreen、panZoom。
- Mermaid SVG 文件名生成。
- Code 下载文件名生成。
- controls config 可按 code/table/mermaid 粒度关闭按钮。
- icons 和 translations 覆盖控件图标、复制、下载、全屏、重试文案。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*Controls*"
```

**Step 3: Minimal implementation**

UI 控件遵循现有 Material 3 和图标风格。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:assembleDebug
```

## Task 14：Shiki 等效高亮层

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/ShikiParityPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/code/ShikiParityTheme.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/code/ShikiLanguageRegistry.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/syntax/SyntaxHighlighter.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/ShikiParityPluginTest.kt`

**Step 1: Write tests**

覆盖：

- 完成态代码块走 Shiki parity token。
- Shiki parity token 快照与官方 `github-light`、`github-dark` 基线接近。
- 流式长代码仍可降级到现有轻量高亮。
- 未支持语言回退纯文本，但控件仍完整。
- light/dark theme token 都存在。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*ShikiParityPluginTest*"
```

**Step 3: Minimal implementation**

先新增 Shiki parity theme、语言注册表和 token 快照测试。现有 `SyntaxHighlighter` 只允许作为流式降级和 unsupported language 回退。若快照无法对齐官方 Shiki，完成态必须接入本地 Shiki JS 资产或 TextMate 规则引擎，资产同样需要 manifest、license、SHA-256。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*ShikiParityPluginTest*"
./gradlew :app:compileDebugKotlin
```

## Task 15：CJK 排版插件

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/CjkMarkdownPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/cjk/CjkLineBreakPolicy.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/CjkMarkdownPluginTest.kt`

**Step 1: Write tests**

覆盖：

- 中文标点不触发表格、列表、setext heading 误判。
- 中文和英文混排不被盲目插空格。
- 中文和 inline code 边界保持原文。
- 中文和数学、链接、图片共存时 raw/display 映射稳定。
- `dir=auto` 能识别 RTL 文本，`dir=ltr/rtl` 能强制文本方向。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*CjkMarkdownPluginTest*"
```

**Step 3: Minimal implementation**

只做 display 层换行和标点策略，不改 raw content，不在 parser 里塞 CJK 特例。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*CjkMarkdownPluginTest*"
```

## Task 16：caret 和 smooth reveal

**Files:**

- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/plugins/CaretSmoothPlugin.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/animation/StreamdownCaret.kt`
- Create: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/animation/SmoothRevealPolicy.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/plugins/CaretSmoothPluginTest.kt`

**Step 1: Write tests**

覆盖：

- caret 只在流式尾部显示。
- caret 支持 block 和 circle。
- caret 不进入复制、下载、分享内容。
- smooth reveal 只影响 tail node，不重挂载 committed nodes。
- `mode=static` 禁用流式 caret 和 smooth。
- `animated` 回调只在动画开始和结束时触发一次。
- 无障碍减少动态效果时禁用 smooth reveal。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*CaretSmoothPluginTest*"
```

**Step 3: Minimal implementation**

用 `SmoothRevealPolicy` 控制动画窗口，默认只在 `isStreaming && tailBlocks.isNotEmpty()` 时启用。关闭动画时直接渲染完整 tail。

**Step 4: Verify**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*CaretSmoothPluginTest*"
./gradlew :app:assembleDebug
```

## Task 17：性能和 telemetry

**Files:**

- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streamdown/StreamdownTelemetry.kt`
- Create: `app1/app/src/test/java/com/android/everytalk/ui/components/streamdown/StreamdownPerformanceTest.kt`

**Step 1: Write tests**

覆盖：

- 5 万字符 parse 不抛异常。
- 逐 token repair 结果稳定。
- fallback reason 可统计。

**Step 2: Run tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*StreamdownPerformanceTest*"
```

**Step 3: Minimal implementation**

Debug 构建记录：

- parse duration
- repair duration
- node count
- fallback reason
- mermaid render duration
- syntax highlight duration
- cjk normalization duration
- smooth reveal skipped/enabled

**Step 4: Verify**

```bash
cd app1
./gradlew :app:compileDebugKotlin
```

## Task 18：总验证

**Files:**

- All touched files

**Step 1: Focused tests**

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "*Streamdown*"
./gradlew :app:testDebugUnitTest --tests "*StreamBlockParserTest*"
./gradlew :app:testDebugUnitTest --tests "*StreamBlocksRendererRoutingTest*"
./gradlew :app:testDebugUnitTest --tests "*InlineMarkdownParserTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownElementPolicyTest*"
./gradlew :app:testDebugUnitTest --tests "*MarkdownUrlTransformTest*"
```

**Step 2: Compile**

```bash
cd app1
./gradlew :app:compileDebugKotlin
```

**Step 3: UI build**

```bash
cd app1
./gradlew :app:assembleDebug
```

**Step 4: Manual smoke**

在 Debug APK 中输入或回放：

- 代码块逐字输出。
- 数学公式逐字输出。
- Mermaid 图逐字输出。
- 表格逐行输出。
- 图片链接逐字输出。
- 大段中文和英文混排。
- caret 只出现在流式尾部。
- 完成态代码块有 light/dark 高亮。

Expected：无崩溃、无大面积闪烁、无错误点击、无主线程卡死。
