# Web Search Sources Button Fix Implementation Plan

> **For Claude:** Use the Skill tool to load `superpowers:executing-plans` before implementation, then execute this plan task-by-task.

**Goal:** 修复开启联网搜索后聊天消息上方 `"N 页面"` 按钮经常不显示的问题，并避免把普通正文链接误判为搜索来源。

**Architecture:** 搜索来源统一走 `AppStreamEvent.WebSearchResults` 更新到 `Message.webSearchResults`。工具调用结果由一个共享 helper 提取，OpenAI Chat Completions、OpenAI Responses、Gemini 工具循环复用同一逻辑。正文兜底只识别明显来源格式，避免普通链接触发页面按钮。

**Tech Stack:** Kotlin 2.2.10、Jetpack Compose、kotlinx.serialization、JUnit 4。

---

## 现状与根因

### 按钮显示条件

`app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt:1422-1444`

```kotlin
val sourcesExtraction = remember(effectiveContent) {
    WebMarkdownSourcesExtractor.extract(effectiveContent)
}
val pageSources = message.webSearchResults?.takeIf { it.isNotEmpty() } ?: sourcesExtraction.sources
if (pageSources.isNotEmpty()) {
    PageSourcesButton(pageSources = pageSources, ...)
}
```

数据流：

```text
搜索工具结果
  |
  v
AppStreamEvent.WebSearchResults
  |
  v
ApiHandler.processStreamEvent
  |
  v
Message.webSearchResults
  |
  v
ChatMessagesList.PageSourcesButton
```

### 已确认的问题

1. `ApiHandler` 收到 `WebSearchResults` 后会直接覆盖 `updatedMessage.webSearchResults`，多次搜索只保留最后一批来源。
2. `ApiHandler` StreamEnd 阶段当前使用旧的 `currentMessage` 做最终化，存在覆盖风险。
3. `mergeStreamingCompletionMessage()` 只合并 `text/reasoning/parts/contentStarted`，即使改成 `finalizeMessageProcessing(updatedMessage)`，也可能在最终 merge 时丢掉 `webSearchResults`。
4. `GeminiDirectClient` 从 `groundingChunks` 构造 `WebSearchResult` 时 `index = 0`，来源弹窗可能显示 `0. title`。
5. 内置 `web_search`、MCP 搜索工具执行后，结果只作为 tool result 传回模型，没有 emit `WebSearchResults`。
6. 工具循环不只在 `OpenAIDirectClient`，还存在于 `OpenAIResponsesClient` 和 `GeminiDirectClient`。
7. `WebMarkdownSourcesExtractor` 当前只识别独立 Sources 区块，兜底太窄；但如果改成提取正文所有 URL，会把普通链接误当搜索来源。

---

## 范围

### 本次处理

- 保留 StreamEnd 前已经写入的 `message.webSearchResults`。
- 多次 `WebSearchResults` 事件合并去重，避免后一次搜索覆盖前一次搜索。
- 修正 Gemini 直连 grounding 来源 index。
- 从搜索类工具结果中提取来源并 emit `AppStreamEvent.WebSearchResults`。
- 覆盖 `OpenAIDirectClient`、`OpenAIResponsesClient`、`GeminiDirectClient` 三个工具循环。
- 放宽正文来源兜底，但只识别明显来源格式。
- 补单元测试和编译验证。

### 本次不处理

- 不尝试恢复代理已经丢弃的 Gemini grounding metadata。
- 不改 `PageSourcesButton` UI。
- 不改搜索服务请求参数。
- 不改数据库 schema。
- 不提交 git commit。

---

## 关键设计

### 搜索工具判定

新增共享 helper，只把明确搜索工具当作来源捕获对象：

```text
toolName == "web_search"
toolName == "search"
toolName.endsWith("_search")
toolName.contains("web_search")
toolName.contains("search_web")
toolName in {"web_search_exa", "firecrawl_search", "brave_web_search", "tavily_search", "serpapi_search"}
```

不使用裸的 `contains("search")`，避免 `research`、`semantic_research`、`knowledge_search_notes` 这类工具名误判。不把 `webfetch`、`fetch`、`crawl`、`scrape`、`browser` 单独视为搜索工具，避免网页读取工具把单页内容误当搜索结果。`toolName == "search"` 只用于结构化结果提取；纯文本 URL fallback 限 `web_search`、`*_search`、`web_search*`、`search_web*` 和白名单工具。

### 工具结果提取

优先解析结构化 JSON：

```text
JsonObject.results[]       -> title, url/href/link, snippet/content/text
JsonObject.data.results[]  -> title, url/href/link, snippet/content/text
JsonObject.data.webPages.value[] -> name/title, url, snippet
JsonArray                  -> 同上
```

结构化解析失败后，只对明确搜索类工具的纯文本结果提取 URL。URL 去重，过滤空 href，index 从 1 开始。

### 正文兜底提取

继续移除完整 Sources 区块。额外允许以下明显来源格式：

```text
## Sources
- [Title](https://example.com)

参考来源:
[1] https://example.com

[^1]: https://example.com

[[1]](https://example.com)
```

不从普通正文中提取所有 Markdown 链接。比如“官网是 https://example.com”不应生成 `"1 页面"`。

---

## Task 1: 修复 ApiHandler 来源合并与 StreamEnd 丢来源

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt:64-71`
- Modify: `app1/app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt:839-841`
- Modify: `app1/app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt:876-889`
- Test: `app1/app/src/test/java/com/android/everytalk/statecontroller/ApiHandlerStreamCompletionMergeTest.kt`

**Step 1: 写失败测试**

在 `ApiHandlerStreamCompletionMergeTest` 增加测试：

```kotlin
@Test
fun `merge preserves web search results from finalized message`() {
    val source = WebSearchResult(
        index = 1,
        title = "Result",
        href = "https://example.com/result",
        snippet = "snippet"
    )
    val synced = Message(
        id = "msg-1",
        text = "final text",
        sender = Sender.AI,
    )
    val finalized = Message(
        id = "msg-1",
        text = "final text",
        sender = Sender.AI,
        webSearchResults = listOf(source)
    )

    val merged = mergeStreamingCompletionMessage(
        syncedMessage = synced,
        finalizedMessage = finalized
    )

    assertEquals(listOf(source), merged.webSearchResults)
}
```

同时补一个反向测试：`finalizedMessage.webSearchResults` 为空时保留 `syncedMessage.webSearchResults`。

新增多次 `WebSearchResults` 合并 helper 测试。先抽出一个 internal helper，例如：

```kotlin
internal fun mergeWebSearchResults(
    existing: List<WebSearchResult>?,
    incoming: List<WebSearchResult>,
): List<WebSearchResult>
```

测试要求：

```kotlin
@Test
fun `merge web search results appends deduplicates and reindexes`() {
    val first = WebSearchResult(1, "A", "https://example.com/a", "")
    val duplicate = WebSearchResult(1, "A again", "https://example.com/a", "new")
    val second = WebSearchResult(9, "B", "https://example.com/b", "")

    val merged = mergeWebSearchResults(
        existing = listOf(first),
        incoming = listOf(duplicate, second)
    )

    assertEquals(listOf("https://example.com/a", "https://example.com/b"), merged.map { it.href })
    assertEquals(listOf(1, 2), merged.map { it.index })
    assertEquals("A", merged[0].title)
}
```

**Step 2: 跑测试确认失败**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.ApiHandlerStreamCompletionMergeTest"
```

Expected: 新增测试失败，原因是 `webSearchResults` 没有被 merge 保留，且多批 `WebSearchResults` 没有合并 helper。

**Step 3: 最小实现**

修改 `mergeStreamingCompletionMessage()`：

```kotlin
internal fun mergeStreamingCompletionMessage(syncedMessage: Message, finalizedMessage: Message): Message {
    val syncedThinkExtraction = extractThinkTagContent(syncedMessage.text)
    return syncedMessage.copy(
        text = if (syncedThinkExtraction.changed) finalizedMessage.text else syncedMessage.text,
        reasoning = finalizedMessage.reasoning ?: syncedMessage.reasoning,
        parts = finalizedMessage.parts.ifEmpty { syncedMessage.parts },
        webSearchResults = finalizedMessage.webSearchResults
            ?.takeIf { it.isNotEmpty() }
            ?: syncedMessage.webSearchResults,
        contentStarted = true,
    )
}
```

新增 helper：

```kotlin
internal fun mergeWebSearchResults(
    existing: List<WebSearchResult>?,
    incoming: List<WebSearchResult>,
): List<WebSearchResult> {
    return (existing.orEmpty() + incoming)
        .filter { it.href.isNotBlank() }
        .distinctBy { it.href }
        .mapIndexed { index, result -> result.copy(index = index + 1) }
}
```

修改 `WebSearchResults` 分支：

```kotlin
is AppStreamEvent.WebSearchResults -> {
    updatedMessage = updatedMessage.copy(
        webSearchResults = mergeWebSearchResults(
            existing = updatedMessage.webSearchResults,
            incoming = appEvent.results
        )
    )
}
```

修改 Finish 分支：

```kotlin
val finalizedMessage = currentMessageProcessor.finalizeMessageProcessing(updatedMessage)
```

**Step 4: 跑测试确认通过**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.ApiHandlerStreamCompletionMergeTest"
```

Expected: PASS。

---

## Task 2: 修复 Gemini 直连 grounding index

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt:859-875`

**Step 1: 写失败测试或最小核验**

如果 `GeminiDirectClient` 当前没有易构造的流式测试，至少在实现后用回读核验确认：

```kotlin
chunks.mapIndexedNotNull { index, chunkElement ->
    ...
    WebSearchResult(
        index = index + 1,
        ...
    )
}
```

**Step 2: 最小实现**

把 `chunks.mapNotNull` 改成 `chunks.mapIndexedNotNull`，并把 `index = 0` 改成 `index = index + 1`。

**Step 3: 编译验证**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL。

---

## Task 3: 新增搜索工具结果提取 helper

**Files:**
- Create: `app1/app/src/main/java/com/android/everytalk/data/network/WebSearchToolResultExtractor.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/data/network/WebSearchToolResultExtractorTest.kt`

**Step 1: 写失败测试**

测试覆盖：

- `web_search` 的 `{ok:true, results:[{title,url,snippet}]}`
- `search` 的结构化 `{results:[{title,url,snippet}]}` 可以提取
- `search` 的纯文本 URL 不提取
- `web_search_exa` 的 `{results:[{title,href,text}]}`
- `firecrawl_search` 的纯文本 URL
- 中文标点结尾 URL 会清理，例如 `https://example.com。`、`https://example.com/news，`
- `data.webPages.value[]` 结构
- 重复 URL 去重
- `webfetch` 不提取
- 空 URL 不返回结果

**Step 2: 跑测试确认失败**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.data.network.WebSearchToolResultExtractorTest"
```

Expected: FAIL，类不存在。

**Step 3: 最小实现**

新增 `internal object WebSearchToolResultExtractor`，暴露：

```kotlin
internal object WebSearchToolResultExtractor {
    fun extract(toolName: String, result: JsonElement): List<WebSearchResult>
}
```

实现要求：

- `isSearchToolName(toolName)` 为 false 时返回空列表。
- `toolName == "search"` 允许结构化结果提取，但不触发纯文本 URL fallback。
- 结构化结果从 `results`、`data.results`、`data.webPages.value` 提取。
- 字段兼容 `url`、`href`、`link`。
- title 兼容 `title`、`name`，空时用 href。
- snippet 兼容 `snippet`、`content`、`text`、`summary`。
- 纯文本 fallback 使用 `Regex("""https?://[^\s)>]+""")`。
- URL `trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'', '。', '，', '；', '：', '）', '】', '》')`。
- 去重后重新分配 `index = size + 1`。

**Step 4: 跑测试确认通过**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.data.network.WebSearchToolResultExtractorTest"
```

Expected: PASS。

---

## Task 4: 三个工具循环 emit WebSearchResults

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/data/network/OpenAIDirectClient.kt:557-618`
- Modify: `app1/app/src/main/java/com/android/everytalk/data/network/OpenAIResponsesClient.kt:107-127`
- Modify: `app1/app/src/main/java/com/android/everytalk/data/network/GeminiDirectClient.kt:119-140`

**Step 1: 写或补测试**

如果现有客户端流式测试难以直接构造，优先依赖 Task 3 的 helper 单测，并在本 Task 强制回读确认三个调用点都执行：

```kotlin
val webResults = WebSearchToolResultExtractor.extract(toolInfo.name, result)
if (webResults.isNotEmpty()) {
    send(AppStreamEvent.WebSearchResults(webResults))
}
```

对应 Gemini 循环变量名为 `toolName`：

```kotlin
val webResults = WebSearchToolResultExtractor.extract(toolName, result)
if (webResults.isNotEmpty()) {
    send(AppStreamEvent.WebSearchResults(webResults))
}
```

强制核验标准：

- `OpenAIDirectClient.kt` 必须出现 `WebSearchToolResultExtractor.extract(toolInfo.name, result)`。
- `OpenAIResponsesClient.kt` 必须出现 `WebSearchToolResultExtractor.extract(toolInfo.name, result)`。
- `GeminiDirectClient.kt` 必须出现 `WebSearchToolResultExtractor.extract(toolName, result)`。
- 三个调用点后都必须有 `if (webResults.isNotEmpty()) { send(AppStreamEvent.WebSearchResults(webResults)) }`。

**Step 2: 实现 OpenAIDirectClient**

在工具执行成功并得到 `result` 后，加入提取和 emit。位置在图片 `_images` 分支处理之前，确保原始结果仍可读。

**Step 3: 实现 OpenAIResponsesClient**

在 `result` 写入 `conversationInput` 前加入提取和 emit。

**Step 4: 实现 GeminiDirectClient**

在 MCP tool result 生成 `toolResponses` 前加入提取和 emit。

**Step 5: 编译验证**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL。

---

## Task 5: 收窄并增强正文来源兜底

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/WebMarkdownSourcesExtractor.kt`
- Test: `app1/app/src/test/java/com/android/everytalk/ui/components/WebMarkdownSourcesExtractorTest.kt`

**Step 1: 写失败测试**

新增测试：

```kotlin
@Test
fun `does not treat ordinary inline links as page sources`() {
    val input = "官网是 https://example.com，也可以看 [文档](https://docs.example.com)。"

    val result = WebMarkdownSourcesExtractor.extract(input)

    assertEquals(input, result.displayText)
    assertTrue(result.sources.isEmpty())
}
```

新增正文中间 citation 反例，防止误删正文：

```kotlin
@Test
fun `does not extract numbered citation urls from middle of answer`() {
    val input = """
        我先说明背景。

        [1] https://example.com/inline

        然后继续给出结论，这一段仍然是正文。
    """.trimIndent()

    val result = WebMarkdownSourcesExtractor.extract(input)

    assertEquals(input, result.displayText)
    assertTrue(result.sources.isEmpty())
}
```

新增明显来源格式测试：

```kotlin
@Test
fun `extracts numbered citation urls without sources header`() {
    val input = """
        这是回答正文。

        [1] https://example.com/news
        [2] Example https://example.com/other
    """.trimIndent()

    val result = WebMarkdownSourcesExtractor.extract(input)

    assertEquals(2, result.sources.size)
    assertEquals("https://example.com/news", result.sources[0].href)
    assertFalse(result.displayText.contains("https://example.com/news"))
    assertEquals("这是回答正文。", result.displayText.trim())
}
```

新增中文标点 URL 清理测试：

```kotlin
@Test
fun `trims chinese punctuation from citation urls`() {
    val input = """
        这是回答正文。

        [1] https://example.com/news。
        [2] https://example.com/other，
    """.trimIndent()

    val result = WebMarkdownSourcesExtractor.extract(input)

    assertEquals("https://example.com/news", result.sources[0].href)
    assertEquals("https://example.com/other", result.sources[1].href)
}
```

新增脚注格式测试：

```kotlin
@Test
fun `extracts footnote urls as sources`() {
    val input = """
        这是回答正文。[^1]

        [^1]: https://example.com/source
    """.trimIndent()

    val result = WebMarkdownSourcesExtractor.extract(input)

    assertEquals(1, result.sources.size)
    assertFalse(result.displayText.contains("[^1]:"))
}
```

**Step 2: 跑测试确认失败**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.WebMarkdownSourcesExtractorTest"
```

Expected: 新增测试失败。

**Step 3: 最小实现**

实现规则：

- 保留原 Sources 区块提取和移除逻辑。
- 新增从文末连续 citation 行提取的逻辑。
- citation 行匹配 `[1] https://...`、`[2] Title https://...`、`[^1]: https://...`、`[[1]](https://...)`。
- 只移除被识别为来源的 citation 行。
- 普通正文链接不提取。

**Step 4: 跑测试确认通过**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.WebMarkdownSourcesExtractorTest"
```

Expected: PASS。

---

## Task 6: 全量相关验证

**Files:**
- No code changes.

**Step 1: 跑相关单测**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.ApiHandlerStreamCompletionMergeTest" --tests "com.android.everytalk.data.network.WebSearchToolResultExtractorTest" --tests "com.android.everytalk.ui.components.WebMarkdownSourcesExtractorTest"
```

Expected: PASS。

**Step 2: 跑 Kotlin 编译**

Run:

```powershell
cd app1
& ".\gradlew.bat" :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL。

**Step 3: 回读核验**

检查以下内容：

```powershell
rg -n "WebSearchToolResultExtractor|mergeWebSearchResults|webSearchResults = finalizedMessage.webSearchResults|takeIf \\{ it\\.isNotEmpty\\(\\) \\}|finalizeMessageProcessing\\(updatedMessage\\)|mapIndexedNotNull" app/src/main/java app/src/test/java
```

Expected:

- 三个客户端都调用 `WebSearchToolResultExtractor.extract(...)`。
- `ApiHandler` 多次 `WebSearchResults` 使用 `mergeWebSearchResults(...)` 合并去重。
- `mergeStreamingCompletionMessage()` 保留 `webSearchResults`。
- Finish 分支使用 `updatedMessage` 最终化。
- `GeminiDirectClient` grounding chunks 使用 `mapIndexedNotNull`，index 从 1 开始。
- 测试文件存在并覆盖正反例。

---

## 覆盖矩阵

| 路径 | 处理点 | 结果 |
|------|--------|------|
| Gemini 直连 grounding | index 从 1 开始，ApiHandler 最终化和 merge 保留来源 | 覆盖 |
| 内置 web_search | OpenAIDirectClient / OpenAIResponsesClient 捕获 tool result | 覆盖 |
| MCP 搜索工具 | 三个工具循环捕获 search tool result | 覆盖 |
| Gemini MCP 工具搜索 | GeminiDirectClient 捕获 tool result | 覆盖 |
| Gemini+NewAPI 转文本引用 | WebMarkdownSourcesExtractor citation 兜底 | 部分覆盖 |
| Gemini+NewAPI 代理丢弃 metadata | 客户端不可见 | 不覆盖 |
| 普通正文 URL | 不作为来源按钮 | 防误判 |

---

## 完成标准

- 搜索类工具返回 URL 时，最终消息持有 `webSearchResults`。
- StreamEnd 后 `webSearchResults` 不丢失。
- 普通正文链接不触发 `"N 页面"` 按钮。
- 相关单测通过。
- `:app:compileDebugKotlin` 通过。
