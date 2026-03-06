# EveryTalk 接近 Gemini 的流式渲染新架构方案

## 1. 背景与目标

当前 EveryTalk 的流式输出主要还是“累积字符串 -> 整段 Markdown / 富文本渲染”的路线。这个路线在普通文本下还能工作，但一旦叠加以下内容，就很容易出现整段重组、闪烁、高度抖动、尾部来回跳动等问题：

- 数学公式
- 表格
- 代码块
- reasoning 分段更新
- 列表项与消息体的多状态源混写

而 Gemini / AGSA 的逆向结果显示，它更接近以下模型：

```text
累积全文字符串
-> 原生解析器
-> 结构化文档模型（Block / Atom）
-> UI 层做局部 diff
-> 只更新变动节点
```

因此，本方案的目标不是“继续优化整段 Markdown 渲染”，而是把 EveryTalk 的流式输出系统升级为：

**结构化文档流式渲染系统**。

### 1.1 核心目标

1. 流式阶段只更新受影响 block，不整条消息重建。
2. 数学公式在“闭合后”可即时转换为正式渲染结果。
3. reasoning、正文、代码块、表格互不干扰。
4. 完成态可以做一次最终精排，但不能出现明显闪屏。
5. 新架构要允许后续逐步逼近 Gemini，而不是一次性重写全部 UI。

### 1.2 非目标

以下内容不属于第一阶段必须完成的范围：

1. 完整复刻 Gemini 的原生 TeX 引擎。
2. 一次性支持全部 LaTeX 环境。
3. 一步到位替换整个现有 Markdown / 富文本系统。
4. 首阶段就达到 Gemini 同等性能与视觉一致性。

本方案强调的是：

**先把架构方向纠正，再逐步提升数学与渲染能力。**

### 1.3 当前已落地（2026-03-06）

以下主链已经开始按新架构落地：

1. `StreamingMessageStateManager` 已同时维护原始流式文本与 `StreamingRenderState`。
2. `StreamingRenderState` 已同步产出结构化 `blocks`、`blocksHash`、`hasPendingMath`。
3. `StreamBlockParser` 已支持 `${'$'}...${'$'}`、`${'$'}${'$'}...${'$'}${'$'}`、`\(...\)`、`\[...\]` 四类数学边界。
4. `ChatMessagesList` 的 AI 正文流式主路径已切到 `StreamBlocksRenderer`，不再默认依赖整段 `EnhancedMarkdownText` 订阅。
5. `StreamBlocksRenderer` 已使用稳定 `segmentId`，并为每个 segment 固定独立 `Message` 快照，避免前缀 block 因整条 `message.text` 变化被拖着重组。
6. `MessageItemsController` 在流式阶段已优先复用 `StreamingRenderState`，避免重复全文 parse。

当前仍未完成的部分：

1. 真正的增量 tokenizer / 增量 AST。
2. 数学块内部更细粒度的 Atom 生命周期。
3. 表格 / 代码 / reasoning 的统一 block 协调器。
4. 最终完成态的精排与缓存进一步下沉。

---

## 2. 问题本质

当前问题并不是“数学库不够强”，而是**渲染抽象层级太低**。

### 2.1 当前系统的典型问题链路

```text
chunk 到达
-> 追加到 message.text
-> 整条消息重新走 Markdown / 富文本渲染
-> 数学 / 表格 / 代码块重新排版
-> 整个气泡重组
-> 闪烁 / 抖动 / 高度变化
```

### 2.2 现有架构的根因

1. **消息正文是唯一渲染输入**，导致所有内容共享同一个重渲染边界。
2. **UI 直接消费整段字符串**，而不是消费结构化 block。
3. **数学渲染缺少独立生命周期**，未闭合、闭合、正式渲染、失败回退都混在一起。
4. **流式状态源并存**，会导致“消息列表已更新”和“流式缓冲区还在推”相互打架。
5. **Compose 订阅层级过高**，导致 block 级变化放大成 message 级重组。

### 2.3 新架构必须解决的核心矛盾

新架构必须同时满足：

1. 流式阶段可持续追加文本。
2. 数学可局部闭合并局部升级为正式渲染。
3. UI 更新边界必须从“整条消息”下沉到“单个 block”。

---

## 3. 总体架构

建议采用六层架构：

```text
Provider Stream
-> Accumulated Text Layer
-> Incremental Token Layer
-> Render Block Layer
-> Math AST / Layout Layer
-> UI Diff Render Layer
```

### 3.1 第 1 层：原始流输入层

职责：

- 接收 provider 的 chunk
- 维护每条消息的 `accumulatedText`
- 记录完成态 `isComplete`

特征：

- 不关心 UI
- 不做 Markdown 渲染
- 不直接修改整条消息富文本

建议模型：

```kotlin
data class StreamingSourceState(
    val messageId: String,
    val accumulatedText: String,
    val isComplete: Boolean,
)
```

### 3.2 第 2 层：增量词法层

职责：

- 从 `accumulatedText` 增量切出稳定 token
- 只重扫“最后一个不安全边界”之后的内容

必须支持的 token：

- `TextToken`
- `MathTokenRaw`
- `MathTokenRendered`
- `InlineCodeToken`
- `CodeFenceToken`
- `TableToken`
- `ParagraphBreakToken`

建议模型：

```kotlin
sealed interface StreamToken {
    val id: String
    val messageId: String
    val rangeStart: Int
    val rangeEnd: Int
}

data class TextToken(...): StreamToken

data class MathTokenRaw(
    override val id: String,
    override val messageId: String,
    override val rangeStart: Int,
    override val rangeEnd: Int,
    val source: String,
    val delimiterType: MathDelimiterType,
    val isClosed: Boolean,
): StreamToken
```

### 3.3 第 3 层：Block 模型层

职责：

- 将 token 列表映射为更稳定的渲染 block
- 把“可视更新单位”从字符串变成 block

建议 block：

- `TextBlock`
- `MathInlineBlock`
- `MathDisplayBlock`
- `CodeBlock`
- `TableBlock`
- `ReasoningBlock`
- `ParagraphBlock`

建议模型：

```kotlin
sealed interface RenderBlock {
    val id: String
    val messageId: String
}

data class TextBlock(...): RenderBlock

data class MathInlineBlock(
    override val id: String,
    override val messageId: String,
    val source: String,
    val ast: MathAst?,
    val renderState: MathRenderState,
): RenderBlock
```

### 3.4 第 4 层：数学 AST / 布局层

职责：

- 将 `MathTokenRaw` 转为 `MathAst`
- 将 `MathAst` 转为可渲染布局节点

建议分两阶段：

#### 第一阶段

- 自己做数学 token 切分与生命周期管理
- 已闭合 token 才交给数学渲染器
- 数学渲染结果只替换对应 block

#### 第二阶段

- 自己做轻量原生数学 parser
- 自己输出结构化数学布局树
- 摆脱图片式公式整体替换

### 3.5 第 5 层：渲染状态层

职责：

- 管理每条消息的 token / block / math 状态
- 对 UI 提供单一事实源

建议模型：

```kotlin
data class StreamingMessageRenderState(
    val messageId: String,
    val accumulatedText: String,
    val tokens: List<StreamToken>,
    val blocks: List<RenderBlock>,
    val isComplete: Boolean,
)
```

关键原则：

- UI 不再直接把 `message.text` 当作流式真相源
- UI 优先消费 `StreamingMessageRenderState.blocks`
- `messages` 只保存消息壳与完成态存档

### 3.6 第 6 层：UI 渲染层

职责：

- 只根据 block 列表渲染消息体
- 只对变化的 block 局部重组

关键原则：

1. `LazyColumn` 的 item key 用 `message.id`
2. 消息内部 block key 用 `block.id`
3. 数学 block 单独 composable
4. reasoning block 单独 composable
5. 不允许在子组件里再回查整条消息字符串做二次渲染

---

## 4. 数学流式策略

### 4.1 为什么不能继续“每个 chunk 都重跑整段数学”

因为当前技术条件下，这样做几乎必然导致：

- 数学 block 高度频繁变化
- Compose 行内布局抖动
- 图片式或重型公式渲染器整体替换
- 整条消息重新测量

### 4.2 推荐的数学流式生命周期

建议数学 block 使用四态：

```kotlin
enum class MathRenderState {
    RAW,
    PARSING,
    RENDERED,
    FAILED,
}
```

状态说明：

- `RAW`：公式还没闭合，只显示原始内容或轻量占位
- `PARSING`：公式已闭合，后台解析中
- `RENDERED`：公式已成功转换为正式数学渲染
- `FAILED`：解析失败，回退原始文本展示

### 4.3 数学闭合策略

流式阶段不要尝试渲染未闭合公式。

只在以下条件成立时触发正式数学渲染：

1. `$...$` 已匹配闭合
2. `\(...\)` 已匹配闭合
3. `\[...\]` 已匹配闭合
4. code fence 内部的 `$` 不参与数学识别

### 4.4 为什么这仍然“像 Gemini”

Gemini 的关键不是“每个字符都马上渲染成漂亮公式”，而是：

- 内容在流式阶段被结构化处理
- 变化只影响局部 block
- 完整公式出现后局部升级

对 EveryTalk 来说，先做到“闭合即局部升级”已经是非常接近 Gemini 体感的方案。

---

## 5. 增量解析策略

### 5.1 不要每次都全文重扫

建议 tokenizer 保存一个 `safeReparseStart`：

- 最后一个未闭合数学 token 的起点
- 最后一个未闭合 code fence 的起点
- 最后一个未闭合表格段的起点

这样每次新 chunk 到来时：

```text
只从 safeReparseStart 重新扫描
前面的稳定 token 直接复用
```

### 5.2 增量扫描原则

1. 已完全闭合且后续不会被影响的 token 不重建。
2. 只重建最后几个不稳定 token。
3. 新 token 生成后，再映射成 block。

### 5.3 建议接口

```kotlin
interface IncrementalTokenizer {
    fun reparse(
        previous: List<StreamToken>,
        accumulatedText: String,
    ): TokenizeResult
}

data class TokenizeResult(
    val tokens: List<StreamToken>,
    val safeReparseStart: Int,
)
```

---

## 6. 状态管理方案

### 6.1 单一事实源

新架构下，每条流式消息只能有一个主要真相源：

```text
messageId -> StreamingMessageRenderState
```

不能再让以下几者同时主导正文渲染：

- `messages`
- `StreamingBuffer`
- `StreamingMessageStateManager`
- 某个 UI 组件的本地 remember 状态

### 6.2 建议仓库接口

```kotlin
interface StreamingRenderRepository {
    fun observe(messageId: String): StateFlow<StreamingMessageRenderState?>
    suspend fun appendChunk(messageId: String, chunk: String)
    suspend fun complete(messageId: String)
    suspend fun reset(messageId: String)
}
```

### 6.3 reasoning 单独建模

reasoning 不应继续和正文争抢同一消息内容字段。

建议：

- `ReasoningBlock` 独立存在
- reasoning 自己维护 render state
- reasoning 的流式更新不触发正文 block 重建

---

## 7. UI 设计原则

### 7.1 渲染边界下沉

消息列表的最小更新单位必须从：

```text
Message
```

下沉为：

```text
RenderBlock
```

### 7.2 推荐渲染方式

```kotlin
@Composable
fun MessageContent(blocks: List<RenderBlock>) {
    blocks.forEach { block ->
        key(block.id) {
            RenderBlockItem(block)
        }
    }
}
```

### 7.3 数学 block UI 策略

- `RAW`：显示轻量原文
- `PARSING`：保留原文或轻量占位
- `RENDERED`：显示正式公式
- `FAILED`：回退原文，不影响上下文布局

### 7.4 完成态切换策略

消息完成后，可触发一次最终精排：

```text
isComplete = true
-> 全文最终 parse
-> block 列表更新
-> 尽量复用已有 block id
```

注意：

- 最终精排不应清空再重建所有 block
- 要尽量保持 block id 稳定，减少视觉跳变

---

## 8. 实施阶段计划

### 阶段 1：止血重构

目标：切断“整条消息重渲染”路径。

工作项：

1. 新增 `StreamingMessageRenderState`
2. 流式消息 UI 改为读取 `blocks`
3. 流式阶段不再走完整 Markdown 渲染

验收：

1. 普通长文本流式不再整条消息闪烁
2. reasoning 更新不影响正文文本块

### 阶段 2：增量 tokenizer

目标：把字符串流升级为 token 流。

工作项：

1. 实现 `TextToken`
2. 实现 `MathTokenRaw`
3. 实现 code fence / inline code 基本识别
4. 引入 `safeReparseStart`

验收：

1. 新 chunk 只影响尾部 token
2. 前面 token 不重复生成

### 阶段 3：block 模型层

目标：把 token 流升级为 block 流。

工作项：

1. 实现 `RenderBlock` 体系
2. token -> block builder
3. UI 全部按 block 渲染

验收：

1. 文本、代码、reasoning 各自独立更新
2. 单个 block 变化不导致整条消息重组

### 阶段 4：数学闭合后局部升级

目标：让公式在闭合时即时升级为正式渲染。

工作项：

1. 实现数学 token 闭合检测
2. 加入 `MathRenderState`
3. 异步解析闭合公式
4. 解析成功后只替换对应 math block

验收：

1. 公式闭合时局部刷新
2. 前后文本 block 稳定不动

### 阶段 5：轻量原生数学 AST

目标：进一步逼近 Gemini 的结构化数学渲染。

工作项：

1. 自研轻量 `MathLexer`
2. 自研轻量 `MathParser`
3. 支持高频子集：
   - 上下标
   - `\frac`
   - `\sqrt`
   - 常见希腊字母
   - 括号
   - `\operatorname`

验收：

1. 不依赖整张位图替换
2. 数学 block 具备更稳定布局

### 阶段 6：完成态精排

目标：完成态与流式态平滑衔接。

工作项：

1. done 后全文最终 parse
2. 表格、复杂公式、复杂 markdown 最终提升
3. 尽量保持 block id 稳定

验收：

1. done 切换不闪
2. 高度变化可控

---

## 9. 模块拆分建议

建议新增以下模块：

```text
app/src/main/java/com/android/everytalk/
  streaming/
    IncrementalTokenizer.kt
    StreamDocumentBuilder.kt
    StreamingRenderRepository.kt
    StreamingRenderState.kt
    TokenizeResult.kt

  math/
    MathLexer.kt
    MathParser.kt
    MathRenderState.kt
    ast/
      MathAst.kt
      nodes/

  ui/components/block/
    RenderBlockItem.kt
    TextBlockView.kt
    MathBlockView.kt
    CodeBlockView.kt
    TableBlockView.kt
    ReasoningBlockView.kt
```

---

## 10. 风险与约束

### 10.1 最大风险

最大风险不是数学 parser，而是**新旧状态系统并存**。

如果迁移过程中仍然让以下系统共同参与渲染：

- `messages`
- 旧 streaming buffer
- UI 本地 remember 状态
- 新 block 状态

那么即使新架构本身正确，最终 UI 仍然会闪。

### 10.2 迁移要求

迁移过程中必须满足：

1. 流式正文只有一个主要事实源。
2. UI 不再从 message 正文回查流式文本。
3. 数学 block 生命周期独立管理。

---

## 11. 验收标准

### 11.1 体验验收

1. 连续长文本流式输出时，不出现整条消息闪烁。
2. 闭合公式出现时，只局部替换该公式 block。
3. 前面已生成文本不反复重排。
4. reasoning 流式更新不干扰正文。
5. done 后最终精排高度变化可控。

### 11.2 技术验收

1. `StreamingMessageRenderState` 成为流式消息的主要渲染输入。
2. UI 内部不再直接把 `message.text` 当作流式正文源。
3. block 更新时 Compose 重组集中在尾部 block，而不是整条消息。
4. 数学 block 存在明确状态机：`RAW / PARSING / RENDERED / FAILED`。

---

## 12. 一句话结论

如果想让 EveryTalk 的流式输出更像 Gemini，真正要做的不是“继续优化整段 Markdown 渲染”，而是：

**把聊天消息从“字符串渲染系统”升级为“结构化文档渲染系统”。**

只有这样，数学公式、代码块、表格、reasoning 才能在流式阶段各自独立演进，并最终做到：

- 局部更新
- 稳定布局
- 尽量少重组
- 逐步逼近 Gemini 的体验
