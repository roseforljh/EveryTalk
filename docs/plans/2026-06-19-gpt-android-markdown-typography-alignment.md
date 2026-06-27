# GPT Android Markdown Typography Alignment Implementation Plan

> **For Claude:** Use `${SUPERPOWERS_SKILLS_ROOT}/skills/collaboration/executing-plans/SKILL.md` to implement this plan task-by-task.

**Goal:** 以 GPT Android 反编译证据为准，逐步把 EveryTalk 主聊天 AI Markdown 排版调到可验证的 GPT 同类行为。

**Architecture:** 先把 GPT Android 的消息正文容器、正文文本、Markdown AST 分发、列表、标题、分割线、代码块链路追到具体文件和行号。EveryTalk 分两条路径落地：原生流式 Markdown 走 `StreamBlocksRenderer.kt`，复杂 Markdown fallback 走 `MarkdownRenderer.kt` 和 Markwon span。

**Tech Stack:** Android, Kotlin, Jetpack Compose, TextView, Markwon, JUnit 4, Robolectric, GPT Android jadx 与 apktool 输出。

---

## 严格原则

- 每个 UI 数值必须先有 GPT 代码证据，或明确标记为 EveryTalk 本地兼容值。
- 禁止把视觉猜测写成 GPT 结论。
- 代码改动必须有对应单测，Compose UI 或 Android UI 相关改动最后跑 `:app:assembleDebug`。
- Android Gradle 命令必须在 `C:\Users\33039\Desktop\EveryTalk\app1` 执行。
- PowerShell 读取含中文文件前执行 `chcp 65001 > $null`，并用 `Get-Content -Encoding UTF8`。

## 当前已证实结论

- GPT 普通正文没有启用 `Justify`，`ge30.java:535` 和 `ge30.java:714` 调 `b5z.b(...)` 时 `qwy textAlign` 走默认值，`b5z.smali` 将默认值落为 `Unspecified`。
- GPT 正文容器满宽，证据是 `ge30.java:503` 和 `ge30.java:676` 的 `b.f(..., 1.0f)`。
- GPT 正文区域 padding 是左 `16dp`、上 `4dp`、右 `0dp`、下 `4dp`，证据是 `ge30.java:518` 和 `ge30.java:699`。
- GPT 正文 `bodyLarge` 是 `16sp / 24sp`，证据链是 `p7y.java:75-91`、`dd00.java:26`、`dc00.java:161-163`。
- GPT 默认 RichTextStyle 的 `paragraphSpacing` 是 `8sp`，证据链是 `mmu.java:11`、`mmu.java:38-44` 和 `pke.java:25-38`。
- GPT Markdown AST 分发入口是 `f440.a(...)`：
  - 引用块外观：`f440.java:77` 调 `ij4.a(...)`，该路径读取 `RichTextStyle.blockQuoteGutter`
  - 列表：`f440.java:104` 调 `zji.a(...)`
  - 分割线：`f440.java:110` 调 `tp40.b(...)`
  - 标题：`f440.java:114` 调 `v150.d(...)`
  - 代码块：`f440.java:125` 和 `f440.java:133` 调 `wr7.a(...)`
  - 表格：`f440.java:165` 调 `ny40.d(...)`
- GPT 列表默认样式来自 `mmu.c(...)`：
  - `zji.java:12`，`markerIndent = 8.sp`
  - `zji.java:13`，`contentsIndent = 4.sp`
  - `zji.java:14`，`itemSpacing = 4.sp`
  - `mmu.java:59-73` 把缺省 `ListStyle` 填成 `zji.a/b/c`
  - `y6z.java:60` 的 `toString()` 会把这些值显示为 `.sp`
- GPT 列表排版用 `yji` measure policy，`zji.smali:1498-1502` 创建 `Lyji`，`yji.smali:649-664` 把 `itemSpacing` 转 px 后乘以项目间隔数量参与高度。

## 当前 EveryTalk 已落地

- `app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt`
  - `BODY_FONT_SIZE_SP = 16f`
  - `BODY_LINE_HEIGHT_SP = 24f`
  - `ASSISTANT_CONTENT_START_PADDING_DP = 16f`
  - `ASSISTANT_CONTENT_TOP_PADDING_DP = 4f`
  - `ASSISTANT_CONTENT_END_PADDING_DP = 0f`
  - `ASSISTANT_CONTENT_BOTTOM_PADDING_DP = 4f`
- `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
  - `compactBodyTextStyle(...)` 使用 `TextAlign.Start`
  - `PlainTextSegment(...)` 使用 `TextAlign.Start`
  - `ComposeInlineMarkdownSegment(...)` 使用 `TextAlign.Start`
  - 普通段落后间距和 heading 前间距已按 GPT `paragraphSpacing = 8sp` 收敛到 `8dp`
  - 标题 H1-H5 字号、行高、字重、alpha 和 H3 italic 已按 GPT 证据映射
  - 分割线使用 `1dp` 厚度、`8dp` 上下 padding、正文色 `0.2` alpha、无额外 after spacer
  - 引用块已按 GPT `blockQuoteGutter` 证据改为 `6dp / 3dp / 6dp` 窄竖条 gutter，内容上下 padding `4dp`，竖条正文色 `0.25` alpha，无整块背景面板
- `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkdownRenderer.kt`
  - `TextView.applyMarkdownLayoutMode(...)` 使用 `LineBreaker.JUSTIFICATION_MODE_NONE`
- `app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt`
  - AI 消息正文容器改成 `fillMaxWidth()`
  - AI 正文 padding 映射到 GPT 证据常量

## 当前证据缺口

- 顶层无序列表 marker 列宽和正文起点已追到 GPT measure policy，并按 `markerIndent + marker自身宽度 + contentsIndent` 落地到 EveryTalk。当前本地 Canvas bullet 宽度为 `5dp`，因此顶层 bullet 正文起点是 `8 + 5 + 4 = 17dp`。
- 有序列表 marker 列宽已改为按层级测量最大 marker 文本宽度，再套 GPT 的 `markerIndent + marker文本宽度 + contentsIndent` 公式，避免长编号挤压正文。
- 嵌套列表已追到 GPT 递归 measure policy：`zji.f` composition local 传递层级，`yji` 每层都按 marker/content 两列测量，`xji` 把 content 放在 marker 列之后。EveryTalk native Compose 列表已从固定 `24dp * level` 改为累计祖先 marker 列宽。
- GPT 默认 ordered marker 样式轮换 `1.`、`a.`、`1)`、`a)` 已追到 `i95.java` 和 `qm2.java`，EveryTalk 已按列表层级取模 4 渲染。
- 普通 Markdown 代码块默认样式已闭环：`hx2/nx2` 走 `wr7.a(...)`，`mmu.java` 补默认 `CodeBlockStyle`，`or7.smali` 套用背景和 padding；未发现普通 Markdown 消息覆盖默认 `ht7`。EveryTalk native Markdown 代码块已切到 `wr7` 风格轻量 renderer。
- GPT 表格默认 `cellPadding = 8`、`borderStrokeWidth = 1dp`、`Lao9` 等宽列布局和非默认 `Lzn9` 横向滚动分支已追到 `qjy.java`、`ijy.smali`、`hm00.smali`、`mmu.java`、`zao.java`，EveryTalk 已落到 `TableRenderer` 的单元格 padding、内容宽度扣减、行分隔线宽和默认等宽列宽公式。
- GPT ChatGPT 表格的 `dividerStyle = k850.s0` 已追到 `zao.java`、`etf.smali`、`hjy.smali`，EveryTalk 已按其语义改为表头底线、body 内部横线、无竖线。颜色仍使用 EveryTalk Material outline 本地映射。
- GPT 引用块外观已追到 `f440.java`、`lmu.java`、`mmu.java`、`ij4.java`、`gj4.java`、`ua.java`，EveryTalk 已移除旧灰底面板和正文降透明，改为 GPT 默认 gutter 数值。
- GPT 引用块内部子内容渲染已追到 `ij4.java:131-134`：先绘制 gutter，再调用 `k440.a(..., gx9Var, ...)` 渲染传入的子内容 composable。EveryTalk 已给 `NativeStreamingMarkdownBlock` 增加 `children`，引用块解析时递归生成子 block，渲染时在 `NativeBlockQuote(...)` 内继续调用 `NativeMarkdownBlocksSegment(...)`。
- EveryTalk 专用代码预览卡、复制按钮、语法高亮和吸顶能力属于本地功能增强，不再作为 GPT 普通 Markdown 代码块默认样式结论。

---

### Task 1: 冻结已证实正文改动

**Files:**
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/streaming/StreamBlocksRendererRoutingTest.kt`
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/markdown/MarkwonCacheTest.kt`

**Step 1: 写正文对齐回归测试**

确认已有测试覆盖：

```kotlin
@Test
fun `compact body text style keeps assistant prose start aligned`() {
    val style = compactBodyTextStyle(
        style = TextStyle(fontSize = 16.sp),
        color = Color.Black,
    )

    assertEquals(TextAlign.Start, style.textAlign)
}
```

如该测试不存在，补到 `StreamBlocksRendererRoutingTest.kt`。

**Step 2: 写正文尺寸和 padding 回归测试**

确认已有测试覆盖：

```kotlin
@Test
fun `assistant prose container follows chatgpt padding evidence`() {
    assertEquals(16f, ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP, 0.001f)
    assertEquals(4f, ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP, 0.001f)
    assertEquals(0f, ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP, 0.001f)
    assertEquals(4f, ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP, 0.001f)
}
```

**Step 3: 跑相关单测**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.compact body text style keeps assistant prose start aligned" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.assistant prose container follows chatgpt padding evidence" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.body line height stays compact inside wrapped chinese list items"
```

Expected: `BUILD SUCCESSFUL`

**Step 4: 验证生产代码映射**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
Select-String -Path app1\app\src\main\java\com\android\everytalk\ui\components\streaming\StreamBlocksRenderer.kt -Encoding UTF8 -Pattern "TextAlign.Start"
Select-String -Path app1\app\src\main\java\com\android\everytalk\ui\components\markdown\MarkdownRenderer.kt -Encoding UTF8 -Pattern "JUSTIFICATION_MODE_NONE"
```

Expected: `TextAlign.Start` 和 `JUSTIFICATION_MODE_NONE` 均存在。

### Task 2: 把无证据标题断言降级

**Files:**
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/markdown/MarkwonCacheTest.kt`
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/streaming/StreamBlocksRendererRoutingTest.kt`
- Modify: `docs/analysis/gpt-android-markdown-typography-evidence.md`

**Step 1: GPT 标题最终字号证据已闭环**

Evidence:

- `fuck-gpt/jadx-out/sources/defpackage/f440.java:112-114`：`jx2` 标题节点交给 `v150.d(...)`。
- `fuck-gpt/jadx-out/sources/defpackage/v150.java:13-14`、`341-354`：标题 style 通过 `new gzf(27)` 和 `mmu.c(...).b.invoke(Integer.valueOf(i), p)` 取得，并与正文 style 合并。
- `fuck-gpt/jadx-out/sources/defpackage/gzf.java:152-177`：H1-H5 标题字号和字重闭环。
- `fuck-gpt/jadx-out/sources/defpackage/a5i.java:23-33`、`42`：`a5i.t0 = FontWeight(700)`。

Current mapping:

- H1：`26sp / 24sp / 700`
- H2：`22sp / 24sp / 700 / alpha 0.7`
- H3：`20sp / 24sp / 700 / italic`
- H4：`18sp / 24sp / 700 / alpha 0.7`
- H5：`16sp / 24sp / 700 / alpha 0.5`
- H6：继承正文 `16sp / 24sp / 400`

**Step 3: 更新证据文档**

在 `docs/analysis/gpt-android-markdown-typography-evidence.md` 写明：

- 已确认 `jx2 = AstHeading`
- 已确认 `f440.java:114` 调 `v150.d(...)`
- 已确认最终字号数值、正文行高继承和 H1-H5 700 字重
- 已落地 H2/H4/H5 alpha 与 H3 italic

**Step 4: 跑标题相关测试**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.heading relative sizes follow chatgpt heading style evidence" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.level four heading uses chatgpt bold eighteen sp scale" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.heading alpha and italic follow chatgpt heading style evidence" --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native heading text spec follows chatgpt heading style evidence"
```

Expected: `BUILD SUCCESSFUL`

### Task 3: 精准逆向 GPT 列表布局

**Files:**
- Read: `fuck-gpt/jadx-out/sources/defpackage/zji.java`
- Read: `fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- Read: `fuck-gpt/jadx-out/sources/defpackage/dym.java`
- Read: `fuck-gpt/apktool-out/smali_classes5/yji.smali`
- Modify: `docs/analysis/gpt-android-markdown-typography-evidence.md`

**Step 1: 固化列表 AST 和 renderer 证据**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
Get-Content -Encoding UTF8 fuck-gpt\jadx-out\sources\defpackage\iy2.java
Get-Content -Encoding UTF8 fuck-gpt\jadx-out\sources\defpackage\wx2.java
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\f440.java -Encoding UTF8 -Pattern "zji\.a"
```

Expected:

- `iy2.toString()` 显示 `AstUnorderedList`
- `wx2.toString()` 显示 `AstOrderedList`
- `f440.java` 列表分支调 `zji.a(...)`

**Step 2: 固化 GPT 默认列表数值**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\zji.java -Encoding UTF8 -Pattern "cet\.n"
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\mmu.java -Encoding UTF8 -Pattern "zji\.a|zji\.b|zji\.c"
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\y6z.java -Encoding UTF8 -Pattern "sp|toString"
```

Expected:

- `zji.a = cet.n(8)`
- `zji.b = cet.n(4)`
- `zji.c = cet.n(4)`
- `y6z` 证明这些是 `.sp`

**Step 3: 解析 `yji` measure policy**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
$p='fuck-gpt\apktool-out\smali_classes5\yji.smali'
$i=0
Get-Content -Encoding UTF8 $p | ForEach-Object {
    $i++
    if ($i -ge 640 -and $i -le 668) { "{0}:{1}" -f $i, $_ }
}
```

Expected: 看到 `Lyji;->d:F` 经 `Lpke;->I0(F)I` 转 px 后乘以项目间隔数量参与总高度。

**Step 4: 只在证据足够时改 EveryTalk 列表常量**

已满足并落地：

- 已确认 GPT `markerIndent` 经 marker 子项 modifier 进入 marker 列宽。
- 已确认 GPT `contentsIndent` 经 marker 子项 modifier 进入正文起点。
- 已确认 GPT `itemSpacing` 作用于项目间高度。
- EveryTalk 顶层无序列表 marker 列宽改为 `8dp + 5dp + 4dp = 17dp`。
- EveryTalk 有序列表 marker 列宽改为使用 `rememberTextMeasurer` 按层级测量最大 marker 文本宽度。
- EveryTalk 嵌套列表行起始缩进改为累计祖先 marker 列宽。
- EveryTalk 列表项空行后 continuation 改为 `children` 子 block，并在递归渲染时保留 `isStreaming`、代码预览、复制、图片点击回调。

**Step 5: 若满足改动条件，先写失败测试**

新增或更新：

```kotlin
@Test
fun `native list spacing follows verified chatgpt item spacing`() {
    val rows = listOf(
        NativeStreamingListItem(text = "first"),
        NativeStreamingListItem(text = "second"),
    )

    assertEquals(EXPECTED.dp, nativeListItemTopSpacing(rows, 1))
}
```

其中 `EXPECTED` 必须来自 GPT 证据。

**Step 6: 跑列表相关测试**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.*list*" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.*list*"
```

Expected: `BUILD SUCCESSFUL`

### Task 4: 分割线和块间距逆向

**Files:**
- Read: `fuck-gpt/jadx-out/sources/defpackage/tp40.java`
- Read: `fuck-gpt/jadx-out/sources/defpackage/mvj.java`
- Read: `fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- Modify: `docs/analysis/gpt-android-markdown-typography-evidence.md`
- Modify only with proof: `app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt`

**Step 1: 确认分割线 AST**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
Get-Content -Encoding UTF8 fuck-gpt\jadx-out\sources\defpackage\hy2.java
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\f440.java -Encoding UTF8 -Pattern "tp40\.b"
```

Expected: `hy2` 是单例 AST 节点，`f440` 将其交给 `tp40.b(...)`。

**Step 2: 追分割线默认 style**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
chcp 65001 > $null
Get-Content -Encoding UTF8 fuck-gpt\jadx-out\sources\defpackage\mvj.java
Select-String -Path fuck-gpt\jadx-out\sources\defpackage\tp40.java -Encoding UTF8 -Pattern "mvj|0.2f|y410\.U|b\.f|qp4\.a"
```

Evidence:

- `fuck-gpt/jadx-out/sources/defpackage/f440.java:107-110`：`hy2` 交给 `tp40.b(...)`。
- `fuck-gpt/jadx-out/sources/defpackage/tp40.java:96-124`、`133`：默认颜色为正文色 `0.2` alpha，默认 spacing 为 paragraphSpacing，modifier 走 vertical padding、fillMaxWidth、height `1.0f`、矩形背景。
- `fuck-gpt/jadx-out/sources/defpackage/mvj.java:8-14`：默认 HorizontalRuleStyle 为 `mvj(null, null)`。
- `fuck-gpt/jadx-out/sources/defpackage/mmu.java:11`、`38-44`：默认 paragraphSpacing 为 `8sp`。
- `fuck-gpt/jadx-out/sources/defpackage/pke.java:25-38`：Sp 通过 fontScale 转为 dp 值。
- `fuck-gpt/apktool-out/smali/androidx/compose/foundation/layout/b.smali:745-771` 与 `fuck-gpt/apktool-out/smali/axw.smali:236-342`：`b.h(..., 1.0f)` 对应 height `1dp`。

Current mapping:

- `HORIZONTAL_RULE_THICKNESS_DP = 1f`
- `HORIZONTAL_RULE_VERTICAL_PADDING_DP = 8f`
- `HORIZONTAL_RULE_COLOR_ALPHA = 0.2f`
- `SPACING_AFTER_DIVIDER_DP = 0f`

### Task 5: Markwon fallback 对齐

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkdownRenderer.kt`
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/markdown/MarkwonCacheTest.kt`

**Step 1: 固化 TextView 禁用 Justify**

已有实现：

```kotlin
justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
```

需补或保留 Robolectric 测试，确认 AI fallback TextView 的 `justificationMode` 不是 `JUSTIFICATION_MODE_INTER_WORD`。

**Step 2: 检查 Markwon list span 是否有 GPT 证据**

如果 Markwon 自定义 span 的缩进、行高、间距未完成 GPT 证据，不得用测试名声称 `chatgpt`。

**Step 3: 跑 Markwon 单测**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest"
```

Expected: `BUILD SUCCESSFUL`

### Task 6: 引用块 gutter 对齐

**Files:**
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt`
- Modify: `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
- Modify: `app1/app/src/test/java/com/android/everytalk/ui/components/streaming/StreamBlocksRendererRoutingTest.kt`
- Modify: `docs/analysis/gpt-android-markdown-typography-evidence.md`

**Evidence:**

- `fuck-gpt/jadx-out/sources/defpackage/f440.java:74-78`：`ax2` 分支调用 `ij4.a(...)`。
- `fuck-gpt/jadx-out/sources/defpackage/lmu.java:205`：字段名为 `blockQuoteGutter`。
- `fuck-gpt/jadx-out/sources/defpackage/mmu.java:78-82`：缺省 `blockQuoteGutter` 回退到 `ij4.a`。
- `fuck-gpt/jadx-out/sources/defpackage/ij4.java:13`：默认值是 `new gj4(0, 0, 0, null, 31)`。
- `fuck-gpt/jadx-out/sources/defpackage/gj4.java:16-21`：默认 `startMargin = 6`、`barWidth = 3`、`endMargin = 6`。
- `fuck-gpt/jadx-out/sources/defpackage/ij4.java:67-75` 与 `mmu.java:11`：`verticalContentPadding = paragraphSpacing / 2 = 4`。
- `fuck-gpt/jadx-out/sources/defpackage/ua.java:55-56`：竖条颜色为当前正文色 `0.25` alpha。

**Current mapping:**

- `BLOCK_QUOTE_START_MARGIN_DP = 6f`
- `BLOCK_QUOTE_BAR_WIDTH_DP = 3f`
- `BLOCK_QUOTE_END_MARGIN_DP = 6f`
- `BLOCK_QUOTE_VERTICAL_CONTENT_PADDING_DP = 4f`
- `BLOCK_QUOTE_BAR_COLOR_ALPHA = 0.25f`
- `NativeBlockQuote(...)` 已移除 `surfaceVariant` 灰底和正文 `0.88` alpha。
- `NativeStreamingMarkdownBlock.children` 已保存引用块内部递归解析结果，`NativeBlockQuote(...)` 对非空 `children` 继续分发到 `NativeMarkdownBlocksSegment(...)`，保持引用内标题、列表等子结构。
- `hashNativeBlocks(...)` 已包含 `children.hashCode()`，避免 native block 缓存只看父引用文本。

**Verification:**

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew --no-daemon testDebugUnitTest --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native block quote uses chatgpt bar gutter without panel background" --console=plain
.\gradlew --no-daemon testDebugUnitTest --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native block quote preserves child block markdown like chatgpt content composable" --tests "com.android.everytalk.ui.components.streaming.StreamBlockParserTest.native markdown block hash changes when recursive children change" --console=plain
```

Expected: `BUILD SUCCESSFUL`

### Task 7: 最终验证

**Files:**
- No code edit unless前面任务已产生改动。

**Step 1: 跑相关单测**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest" --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest"
```

Expected: `BUILD SUCCESSFUL`

**Step 2: 编译 Kotlin**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

**Step 3: 构建 Debug APK**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 4: 检查 diff 空白错误**

Run:

```powershell
cd C:\Users\33039\Desktop\EveryTalk
git diff --check
```

Expected: 无错误。CRLF warning 可记录，但不能有 whitespace error。

## 交付口径

- 可以说：正文层已按 GPT 证据对齐。
- 可以说：列表默认 `8.sp / 4.sp / 4.sp` 已逆向到 GPT 代码，顶层无序列表、有序列表动态 marker 列宽、ordered marker 样式轮换、嵌套 marker 列累计和列表项子内容递归已按当前 GPT 证据落地。
- 可以说：标题、分割线、引用块、普通 Markdown 代码块默认样式已按当前 GPT 逆向证据落地。
- 不能说：EveryTalk 已经完成独立 Markdown AST/domain model 抽层。
- 不能说：GPT 只是简单左对齐。
