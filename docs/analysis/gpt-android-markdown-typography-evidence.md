# GPT Android Markdown 排版证据

## 目标

把 EveryTalk 主聊天 AI 正文排版对齐 GPT Android 的可验证行为，避免凭感觉调字号、对齐和容器宽度。

## 证据链

### 1. 正文没有启用 Justify

- GPT 反编译文件：`fuck-gpt/jadx-out/sources/defpackage/qwy.java`
- 行号：`8-30`
- 证据：
  - `1 = Left`
  - `3 = Center`
  - `4 = Justify`
  - `5 = Start`
  - `0 = Unspecified`

- GPT 消息正文调用点：`fuck-gpt/jadx-out/sources/defpackage/ge30.java`
- 行号：`535`、`714`
- 证据：两处 `b5z.b(...)` 的第 11 个业务参数传入 `null`，该参数在 `b5z.java:93` 是 `qwy r60`，对应 `textAlign`。

- GPT smali 复核：`fuck-gpt/apktool-out/smali/b5z.smali`
- 行号：`633-642`、`1079-1087`、`1241-1256`
- 证据：
  - `0x400` 对应 `p13`，也就是 `qwy textAlign`。
  - `ge30.java` 调用使用的 default mask 是 `241658`，其中包含 `1024 = 0x400`。
  - `qwy` 为默认值时写入 `null`。
  - `qwy == null` 时最终 textAlign 值为 `0`，也就是 `Unspecified`。

结论：GPT 正文没有启用 `Justify`。它走 Compose 默认起始方向排版。

EveryTalk 映射：

- `StreamBlocksRenderer.kt`：正文 `TextAlign.Start`。
- `MarkdownRenderer.kt`：TextView fallback 使用 `JUSTIFICATION_MODE_NONE`。

### 2. 正文基准字号是 16sp / 24sp

- GPT Typography 容器：`fuck-gpt/jadx-out/sources/defpackage/zc00.java`
- 行号：`40-50`、`91-92`
- 证据：构造函数第 10 个参数映射到字段 `j`，`toString()` 显示字段 `j = bodyLarge`。

- GPT Typography 组装：`fuck-gpt/jadx-out/sources/defpackage/p7y.java`
- 行号：`75-91`
- 证据：`new zc00(...)` 的第 10 个参数是 `o6zVar10`，而 `o6zVar10 = dd00.a`。

- GPT bodyLarge token：`fuck-gpt/jadx-out/sources/defpackage/dd00.java`
- 行号：`26`
- 证据：`dd00.a = o6z.b(... dc00.c ... dc00.b ...)`。

- GPT token 数值：`fuck-gpt/jadx-out/sources/defpackage/dc00.java`
- 行号：`161-163`
- 证据：
  - `dc00.b = cet.m(24.0d)`
  - `dc00.c = cet.n(16)`

结论：GPT 当前正文 `bodyLarge` 是 `16sp / 24sp`。

EveryTalk 映射：

- `ChatMarkdownTextStyle.BODY_FONT_SIZE_SP = 16f`
- `ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP = 24f`
- `ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP = 24f`

### 3. GPT 消息正文容器接近满宽，padding 是左 16dp、上下 4dp

- GPT 消息容器：`fuck-gpt/jadx-out/sources/defpackage/ge30.java`
- 行号：`503`、`518`、`676`、`699`
- 证据：
  - `b.f(..., 1.0f)`：宽度填满可用空间。
  - `b.u(..., 0.0f, 44.0f, 0.0f, 0.0f, 13)`：外层顶部留 44dp。
  - `y410.U(..., 16.0f, 4.0f, 0.0f, 4.0f, 4)`：正文区域局部 padding 为左 16dp、上 4dp、右 0dp、下 4dp。

结论：GPT 的整洁感主要来自稳定正文宽度、起始方向排版、较克制的正文 padding，不依赖两端对齐。

EveryTalk 映射：

- 主聊天 `AiMessageItem` 的 `Row`、`Surface`、正文 `Column` 改为 `fillMaxWidth()`。
- 主聊天 AI 正文 `Box` 改为 `fillMaxWidth()`。
- 主聊天 AI 正文 padding 使用：
  - `ASSISTANT_CONTENT_START_PADDING_DP = 16f`
  - `ASSISTANT_CONTENT_TOP_PADDING_DP = 4f`
  - `ASSISTANT_CONTENT_END_PADDING_DP = 0f`
  - `ASSISTANT_CONTENT_BOTTOM_PADDING_DP = 4f`

## EveryTalk 当前落地文件

- `app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkwonCache.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/components/markdown/MarkdownRenderer.kt`
- `app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt`

## 继续逆向到的 Markdown 链路

### 4. GPT Markdown AST 分发

- GPT 反编译文件：`fuck-gpt/jadx-out/sources/defpackage/f440.java`
- 行号：`104`、`110`、`114`、`125`、`133`、`159`、`165`
- 证据：
  - `f440.java:77`：`ax2` 节点交给 `ij4.a(...)`，该路径读取 `RichTextStyle.blockQuoteGutter`。
  - `f440.java:104`：`iy2` 或 `wx2` 列表节点交给 `zji.a(...)`。
  - `f440.java:110`：`hy2` 分割线节点交给 `tp40.b(...)`。
  - `f440.java:114`：`jx2` 标题节点交给 `v150.d(...)`。
  - `f440.java:125`、`133`：`nx2` 和 `hx2` 代码块交给 `wr7.a(...)`。
  - `f440.java:159`：`xx2` 文本/段落节点交给 `hmn.a(...)`。
  - `f440.java:165`：`ey2` 交给 `ny40.d(...)`。

- AST 类型文件：
  - `iy2.java`：`toString()` 返回 `AstUnorderedList(...)`。
  - `wx2.java`：`toString()` 返回 `AstOrderedList(...)`。
  - `jx2.java`：`toString()` 返回 `AstHeading(level=...)`。
  - `hx2.java`：`toString()` 返回 `AstFencedCodeBlock(...)`。
  - `nx2.java`：`toString()` 返回 `AstIndentedCodeBlock(...)`。

结论：GPT 普通 Markdown 不是单个 Text 组件硬渲染，列表、标题、代码块、分割线都有独立 renderer。

EveryTalk 映射：

- 原生流式 Markdown：`StreamBlocksRenderer.kt` 的 `NativeMarkdownBlocksSegment(...)`。
- 复杂 Markdown fallback：`MarkdownRenderer.kt` 的 Markwon TextView。

### 4.1. GPT 默认 paragraphSpacing token

- GPT RichTextStyle 默认填充：`fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- 行号：`11`、`38-44`
- 证据：
  - `mmu.b = cet.n(8)`。
  - `mmu.c(...)` 在 `lmuVar.a == null` 时把 RichTextStyle 的 `paragraphSpacing` 填为 `mmu.b`。

- GPT Sp 到 Dp 转换：`fuck-gpt/jadx-out/sources/defpackage/pke.java`
- 行号：`25-38`
- 证据：`L(long j)` 要求输入单位是 Sp，并用 `m0()` fontScale 乘以数值。默认 fontScale 下 `8sp` 对应 `8dp` 视觉间距。

结论：GPT 默认 RichTextStyle 的段落节奏 token 是 `8sp`。EveryTalk native Markdown 普通段落后间距和 heading 前间距不应继续使用旧的 `12dp / 16dp` 放大值。

EveryTalk 映射状态：

- `ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP = 8f`
- `ChatMarkdownTextStyle.SPACING_BEFORE_HEADING_DP = SPACING_PARAGRAPH_DP`
- `StreamBlocksRenderer.kt` 的 `nativeMarkdownBlockSpacingAfter(...)` 对普通段落后间距和 heading 前间距使用上述 token。

### 5. GPT 列表默认 RichTextStyle

- GPT 列表样式文件：`fuck-gpt/jadx-out/sources/defpackage/zji.java`
- 行号：`12-14`
- 证据：
  - `zji.a = cet.n(8)`
  - `zji.b = cet.n(4)`
  - `zji.c = cet.n(4)`

- GPT RichTextStyle 默认填充：`fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- 行号：`11`、`59-73`
- 证据：
  - `mmu.b = cet.n(8)`，对应 `RichTextStyle(paragraphSpacing=...)`。
  - `mmu.c(...)` 在 `ListStyle` 缺省时用 `zji.a`、`zji.b`、`zji.c` 填充 marker、正文缩进和 item spacing。

- GPT 文本单位文件：`fuck-gpt/jadx-out/sources/defpackage/y6z.java`
- 行号：`42-49`、`60`
- 证据：
  - `y6z.e(...)` 在单位 tag 为 `4294967296L` 时输出 `.sp`。
  - `toString()` 调 `e(this.a)`。

- GPT list measure policy smali：`fuck-gpt/apktool-out/smali_classes5/zji.smali`、`fuck-gpt/apktool-out/smali_classes5/yji.smali`
- 行号：
  - `zji.smali:1498-1502`：创建 `Lyji`，参数包含列表类型、是否启用交互、`qyy`、`itemSpacing`。
  - `yji.smali:649-664`：读取 `Lyji;->d:F`，调用 `Lpke;->I0(F)I` 转 px，再乘以项目间隔数量并加入总高度。
  - `yji.smali:277-359`：内容列测量宽度会从总宽度扣掉最大 marker 宽度。
  - `xji.smali:263-273`、`388-392`：普通 LTR 下 marker 放在 x=0，正文内容放在最大 marker 宽度处。
  - `mjb.smali:1540-1791`：marker 子项逐个套用传入的 `njq` modifier。
  - `bfh.java:92-95`、`97-119`：默认 ordered markers 支持 `1.`、`a.`、`1)`、`a)`，unordered markers 支持 `•`、`◦`、`▸`、`▹`。
  - `i95.java:100-104`：把 marker 样式索引和编号传给 `qeqVar.a.h(...)`；`qm2.java:856-882`：用 `function1Arr[intValue15 % function1Arr.length]` 按层级轮换 marker 函数。
  - `zji.smali:711-728`：读取 composition local `zji.f` 中的当前列表 marker 样式层级。
  - `wji.java:35-37`：列表内容区域用 `zji.f.a(Integer.valueOf(i3 + 1))` 把子列表层级递增传给嵌套内容。
  - `nbc.java:95-108`：对每个 list item 调 `gx9Var.h(yluVar, list.get(i2), ...)`，说明 item content 不是拼成纯字符串，而是继续交给传入的内容 composable 渲染。
  - `yji.smali:74`：measure policy 要求节点数为 `itemCount * 2`，即 marker 子项和 content 子项成对存在。
  - `yji.smali:317`：内容子项测量宽度从总宽度扣掉最大 marker 宽度。
  - `yji.smali:583`：最终宽度由最大 marker 宽度加最大 content 宽度组成。
  - `xji.smali:177-209`：每行高度取 marker/content 高度最大值，再加 `itemSpacing`。
  - `xji.smali:383-392`：普通放置路径中 marker 放在 x=0，content 放在 marker 宽度之后。

结论：GPT 列表默认值可以确认是 `markerIndent = 8.sp`、`contentsIndent = 4.sp`、`itemSpacing = 4.sp`。marker 子项先套 `start = markerIndent`、`end = contentsIndent`，列表正文列再放到最大 marker 子项宽度之后；item spacing 在布局高度中按项目间隔数量生效。嵌套列表不是额外固定 `24dp` 缩进，而是在 parent content 内再次递归执行同一套 marker/content 两列 measure policy；list item 内容也不是纯字符串拼接，而是继续走传入的 content composable。

EveryTalk 映射状态：

- `ChatMarkdownTextStyle.LIST_MARKER_INDENT_DP = 8f`
- `ChatMarkdownTextStyle.LIST_CONTENTS_INDENT_DP = 4f`
- `ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP = LIST_MARKER_INDENT_DP + LIST_BULLET_SIZE_DP + LIST_CONTENTS_INDENT_DP`
- `ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP = 24f`
- `ChatMarkdownTextStyle.LIST_ITEM_SPACING_DP = 4f`
- 顶层无序列表 marker 列宽已经按 GPT 的 `markerIndent + marker自身宽度 + contentsIndent` 公式落地。EveryTalk 的 marker 自身宽度使用本地 Canvas bullet 宽度 `5dp`，因此顶层 bullet 正文起点为 `8 + 5 + 4 = 17dp`。
- 有序列表 marker 列宽已经改为按层级测量最大 marker 文本宽度，再套 `markerIndent + marker文本宽度 + contentsIndent`；这避免 `10.`、`100.` 继续被固定 marker 宽度挤压。
- 有序列表 marker 文本已经按 GPT 默认函数轮换落地：level 0 为 `1.`，level 1 为 `a.`，level 2 为 `1)`，level 3 为 `a)`，level 4 起循环。
- 列表项间距已经按 GPT `itemSpacing = 4.sp` 的逆向证据收紧。
- Native Compose 列表已把嵌套行起始缩进从固定 `LIST_NESTED_INDENT_DP * level` 改为 `nativeListNestedStartPaddingDp(...)`，即累计祖先层 marker 列宽。这样 level 1 无序列表正文起点从旧实现的 `24 + 16 = 40dp` 收到 `17 + 16 = 33dp`，更接近 GPT 递归 measure policy。
- `NativeStreamingListItem.children` 已保存空行后 continuation 解析出的子 block，`NativeListBlock(...)` 在正文列中继续调用 `NativeMarkdownBlocksSegment(...)`，并保留 `isStreaming`、代码预览、复制、图片点击回调。
- `LIST_NESTED_INDENT_DP` 仍保留给旧 Markwon fallback 相关逻辑，不再作为 native Compose 列表缩进依据。

### 6. GPT 标题样式

- GPT Markdown AST 分发：`fuck-gpt/jadx-out/sources/defpackage/f440.java`
- 行号：`112-114`
- 证据：`jx2` 标题节点交给 `v150.d(yluVar, ((jx2) jq30Var).b, ...)`，`jx2.b` 是进入标题样式函数的 level。

- GPT 标题渲染入口：`fuck-gpt/jadx-out/sources/defpackage/v150.java`
- 行号：`13-14`、`341-354`
- 证据：
  - `v150.a = new gzf(27)`。
  - `v150.d(...)` 先读当前正文样式 `wlu.d(uriVar)`，再用 `mmu.c(mmu.b(uriVar)).b.invoke(Integer.valueOf(i), p)` 取得 heading style，并通过 `p.f(...)` 合并。
  - `o6z.b(d, j, ...)` 以当前正文样式为基础，所以 heading 未显式覆盖的 `lineHeight` 会继承正文基准。

- GPT 标题默认样式函数：`fuck-gpt/jadx-out/sources/defpackage/gzf.java`
- 行号：`152-177`
- 证据：
  - `level 1`：`fontSize = cet.n(26)`，`fontWeight = a5i.t0`
  - `level 2`：`fontSize = cet.n(22)`，`fontWeight = a5i.t0`，正文色 alpha `0.7`
  - `level 3`：`fontSize = cet.n(20)`，`fontWeight = a5i.t0`，`new t4i(1)` italic
  - `level 4`：`fontSize = cet.n(18)`，`fontWeight = a5i.t0`，正文色 alpha `0.7`
  - `level 5`：没有字号覆盖，`fontWeight = a5i.t0`，正文色 alpha `0.5`
  - 其他 level：返回当前正文 style。

- GPT 字重常量：`fuck-gpt/jadx-out/sources/defpackage/a5i.java`
- 行号：`23-33`、`42`
- 证据：`a5iVar7 = new a5i(700)`，随后 `t0 = a5iVar7`，所以 `a5i.t0 = FontWeight(700)`。

- GPT level 传递复核：
  - `fuck-gpt/apktool-out/smali_classes7/jj4.smali:2032-2045`、`2255-2281`：构造 `qrj(v4, ...)`。
  - `fuck-gpt/apktool-out/smali_classes7/qrj.smali:71-80`：`qrj(int, xrl)` 把传入的 `p1` 写入 `prj.g`。
  - 结合 `f440.java:112-114` 的 `((jx2) jq30Var).b`，Markdown 标题 level 会继续作为标题样式函数参数。

结论：GPT Markdown 标题的可落地字号/行高/字重映射为：

- `#`：`26sp / 24sp / 700`
- `##`：`22sp / 24sp / 700`
- `###`：`20sp / 24sp / 700`
- `####`：`18sp / 24sp / 700`
- `#####`：`16sp / 24sp / 700`
- `######`：继承正文 `16sp / 24sp / 400`

EveryTalk 映射状态：

- `ChatMarkdownTextStyle.headingFontSizeSp(...)` 已按 GPT 标题字号证据更新。
- `ChatMarkdownTextStyle.headingLineHeightSp(...)` 已统一继承正文 `BODY_LINE_HEIGHT_SP = 24f`。
- `StreamBlocksRenderer.chatGptHeadingTextSpecForLevel(...)` 已将 H1-H5 映射为 `FontWeight.W700`。
- `MarkwonCache.chatHeadingTextWeight(...)` 已将 H1-H5 映射为 `700`。
- `StreamBlocksRenderer.chatGptHeadingTextSpecForLevel(...)` 已将 H2/H4 映射为 `0.7f` alpha，H5 映射为 `0.5f` alpha，H3 映射为 `FontStyle.Italic`。
- `MarkwonCache` 已通过 `ChatTextAlphaSpan` 和 `StyleSpan(Typeface.ITALIC)` 同步兜底路径。

### 7. GPT 分割线默认 RichTextStyle

- GPT Markdown AST 分发：`fuck-gpt/jadx-out/sources/defpackage/f440.java`
- 行号：`107-110`
- 证据：`hy2` 分割线节点交给 `tp40.b(...)`。

- GPT 分割线样式入口：`fuck-gpt/jadx-out/sources/defpackage/tp40.java`
- 行号：`96-124`、`133`
- 证据：
  - `mmu.c(mmu.b(uriVar))` 获取 RichTextStyle。
  - `mvj mvjVar = c.g` 读取 HorizontalRuleStyle。
  - 当 `mvjVar.a == null` 时，颜色走 `xl9.b(0.2f, wlu.c(uriVar))`，即当前正文色 alpha `0.2`。
  - 当 `mvjVar.b == null` 时，间距走 `((pke) uriVar.l(vba.h)).L(c.a.a)`。
  - 最终 modifier 是 `y410.U(..., 0.0f, f2, 0.0f, f2, 5)` 加上下 padding，再 `b.f(..., 1.0f)` fillMaxWidth，再 `b.h(..., 1.0f)` height，再 `y2k.o(..., j, rw4.x)` 绘制矩形背景。

- GPT 默认 HorizontalRuleStyle：`fuck-gpt/jadx-out/sources/defpackage/mvj.java`
- 行号：`8-14`、`48-49`
- 证据：`mvj.c = new mvj(null, null)`，默认 color 与 spacing 都为空，由 `tp40.b(...)` fallback。

- GPT 默认 paragraphSpacing：`fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- 行号：`11`、`38-44`
- 证据：`mmu.b = cet.n(8)`，RichTextStyle 的 `paragraphSpacing` 缺省为 `8sp`。

- GPT Sp 到 Dp 转换：`fuck-gpt/jadx-out/sources/defpackage/pke.java`
- 行号：`25-38`
- 证据：`L(long j)` 要求输入单位是 Sp，并用 `m0()` fontScale 乘以数值。默认 fontScale 下 `8sp` 对应 `8dp` 视觉间距。

- GPT height 语义复核：
  - `fuck-gpt/apktool-out/smali/androidx/compose/foundation/layout/b.smali:675-715`：`b.f(evo, 1.0f)` 是 fill 轴向方法，已在消息容器证据中对应 fillMaxWidth。
  - `fuck-gpt/apktool-out/smali/androidx/compose/foundation/layout/b.smali:745-771`：`b.h(evo, 1.0f)` 创建 SizeElement，只设置 min/max height 为 `1.0f`。
  - `fuck-gpt/apktool-out/smali/axw.smali:236-342`：`y0/A0` 进入 minHeight/maxHeight 约束。

结论：GPT 默认分割线是 fillMaxWidth、height `1dp`、正文色 alpha `0.2`、上下 padding 各 `8dp`，不需要额外 after spacer。

EveryTalk 映射状态：

- `ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP = 1f`
- `ChatMarkdownTextStyle.HORIZONTAL_RULE_VERTICAL_PADDING_DP = 8f`
- `ChatMarkdownTextStyle.HORIZONTAL_RULE_COLOR_ALPHA = 0.2f`
- `ChatMarkdownTextStyle.SPACING_AFTER_DIVIDER_DP = 0f`
- `StreamBlocksRenderer.kt` 的 `HorizontalDivider` 已使用上述常量。

### 8. GPT 引用块默认 RichTextStyle

- GPT Markdown AST 分发：`fuck-gpt/jadx-out/sources/defpackage/f440.java`
- 行号：`74-78`
- 证据：`ax2` 分支调用 `ij4.a(yluVar, dmnVar, xluVar, qyyVar, ...)`。

- GPT RichTextStyle 字段名：`fuck-gpt/jadx-out/sources/defpackage/lmu.java`
- 行号：`205`
- 证据：`toString()` 明确输出 `blockQuoteGutter=`，字段 `d` 类型是 `gj4`。

- GPT 默认引用 gutter：`fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- 行号：`78-82`
- 证据：`gj4 gj4Var = lmuVar.d; if (gj4Var == null) { gj4Var = ij4.a; }`。

- GPT 默认 gutter 构造：`fuck-gpt/jadx-out/sources/defpackage/ij4.java`
- 行号：`6`、`13`
- 证据：`ij4.a = new gj4(0, 0, 0, null, 31)`。

- GPT gutter token：`fuck-gpt/jadx-out/sources/defpackage/gj4.java`
- 行号：`16-21`、`60-76`
- 证据：
  - `startMargin = cet.n(6)`
  - `barWidth = cet.n(3)`
  - `endMargin = cet.n(6)`
  - `verticalContentPadding = null`
  - `gj4.a(...)` 用 `startMargin` 和 `endMargin` 做水平 padding，用 `barWidth` 做 gutter 宽度，并通过 `y2k.o(...)` 绘制圆角背景条。

- GPT 引用内容上下 padding：`fuck-gpt/jadx-out/sources/defpackage/ij4.java`
- 行号：`67-75`、`119`
- 证据：
  - `verticalContentPadding == null` 时，取 `mmu.c(mmu.b(uriVar)).a.a / 2.0f`。
  - `mmu.java:11` 和 `mmu.java:38-44` 证明默认 paragraphSpacing 是 `8sp`。
  - 所以默认引用内容上下 padding 是 `4dp` 视觉值。

- GPT gutter 颜色：`fuck-gpt/jadx-out/sources/defpackage/ua.java`
- 行号：`41`、`55-56`
- 证据：`ua.o0` 对当前内容色执行 `xl9.b(0.25f, currentColor)`，即当前正文色 `0.25` alpha。

- GPT 引用块子内容渲染：`fuck-gpt/jadx-out/sources/defpackage/ij4.java`
- 行号：`131-134`
- 证据：`ij4.a(...)` 先调用 `gj4Var.a(...)` 绘制 gutter，随后调用 `k440.a(..., gx9Var, ...)`，其中 `gx9Var` 来自 `f440.java:77` 传入的子内容 composable。

结论：GPT 引用块默认外观是窄竖条 gutter，不是整块灰底面板。默认数值是左外距 `6dp`、竖条宽 `3dp`、右外距 `6dp`、内容上下 padding `4dp`、竖条颜色为当前正文色 `0.25` alpha。文本内容保持当前正文颜色。引用块内部不是纯字符串降级渲染，而是在 gutter 后继续渲染子 Markdown block 内容。

EveryTalk 映射状态：

- `ChatMarkdownTextStyle.BLOCK_QUOTE_START_MARGIN_DP = 6f`
- `ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_WIDTH_DP = 3f`
- `ChatMarkdownTextStyle.BLOCK_QUOTE_END_MARGIN_DP = 6f`
- `ChatMarkdownTextStyle.BLOCK_QUOTE_VERTICAL_CONTENT_PADDING_DP = 4f`
- `ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_COLOR_ALPHA = 0.25f`
- `StreamBlocksRenderer.kt` 的 `NativeBlockQuote(...)` 已移除整块 `surfaceVariant` 背景和正文 `0.88` alpha，改为当前正文色文本加 `0.25` alpha 竖条。
- `NativeStreamingMarkdownBlock.children` 已保存引用块内部递归解析出的子 block，`NativeBlockQuote(...)` 在 `children` 非空时继续调用 `NativeMarkdownBlocksSegment(...)`，让引用内标题、列表等结构保持原生 block 渲染。
- `StreamingRenderState.hashNativeBlocks(...)` 已把 `children.hashCode()` 纳入 native block hash，避免引用块子结构变化时缓存漏判。

### 9. GPT 代码块默认 RichTextStyle

- GPT 代码块样式文件：`fuck-gpt/jadx-out/sources/defpackage/wr7.java`
- 行号：`8-18`
- 证据：
  - `wr7.a = new o6z(... q3i.Z ... 16777183)`，其中 `q3i.Z` 在 `q3i.java:8` 是 `FontFamily.Monospace`。
  - `wr7.b = xl9.b(0.5f, xl9.e)`，其中 `xl9.e = rw4.d(4291611852L)`，对应 `#CCCCCC`，再叠加 `0.5f` alpha。
  - `wr7.c = y2k.o(bvo.v, b2, rw4.x)`。
  - `wr7.d = cet.n(16)`。

- GPT 默认填充：`fuck-gpt/jadx-out/sources/defpackage/mmu.java`
- 行号：`89-116`
- 证据：
  - `CodeBlockStyle` 缺省时填入 `wr7.a` 文本样式、`wr7.c` modifier、`wr7.d` padding。
  - `wordWrap` 缺省为 `true`。

- GPT modifier 语义：
  - `fuck-gpt/jadx-out/sources/defpackage/ec10.java:17-23`：`rw4.x` 对应 `RectangleShape`。
  - `fuck-gpt/jadx-out/sources/defpackage/o04.java:18-36` 与 `s04.java:28-63`：`y2k.o(...)` 生成的 `o04/s04` 是背景绘制节点，先绘制颜色，再绘制内容。
  - `fuck-gpt/jadx-out/sources/defpackage/y410.java:313-315`：`y410.Q(evo, f)` 生成 `new ejq(f, f, f, f, true, ...)`，即四边同值 padding。
  - `fuck-gpt/apktool-out/smali_classes5/or7.smali:321-330`：`ht7.c` 经 `pke.L(...)` 转换。
  - `fuck-gpt/apktool-out/smali_classes5/or7.smali:368-372`：转换后的 padding 值传给 `Ly410;->Q(Levo;F)Levo;`。

- GPT 上下文覆盖复核：
  - 全局 smali 搜索 `new-instance Lht7` 只发现 `ht7` 默认、`mmu` 默认补全和 `itt` 专用组件覆盖。
  - 普通 Markdown 消息链路未发现覆盖 `CodeBlockStyle` 的 `ht7` 构造点。
  - `itt` 属于专用组件路径，不能作为普通 Markdown 消息代码块默认样式。

结论：GPT 默认代码块正文是等宽字体、wordWrap 开启、背景为 `#CCCCCC` 50% alpha 的矩形背景、内容四边 padding 为 `16sp`。

EveryTalk 映射状态：

- `CodeBlockCard.kt` 已把代码正文内容区改为四边 `16.dp` padding：
  - `GPT_CODE_BLOCK_CONTENT_PADDING_DP = 16f`
  - `Modifier.padding(GPT_CODE_BLOCK_CONTENT_PADDING_DP.dp)`
- `StreamBlocksRenderer.kt` 的 native Markdown `CodeBlock` 分支已切到 `ChatGptMarkdownCodeBlockSegment(...)`。
- `ChatGptMarkdownCodeBlockSegment(...)` 使用 `FontFamily.Monospace`、`TextAlign.Start`、`#CCCCCC` 50% alpha 背景和四边 `16.dp` padding。
- `CodeBlockCard.kt` 保留给非 native/fallback 场景，继续承载 EveryTalk 本地的 Header、预览、复制、吸顶和语法高亮能力。

### 10. GPT 表格默认 RichTextStyle

- GPT 表格 AST 分发：`fuck-gpt/jadx-out/sources/defpackage/f440.java:165`，`ey2` 交给 `ny40.d(...)`。
- GPT 表格渲染入口：`fuck-gpt/jadx-out/sources/defpackage/ny40.java:480`，最终调用 `ijy.a(...)`。
- GPT 表格样式对象：`fuck-gpt/jadx-out/sources/defpackage/qjy.java:98-99`，字段名明确为 `TableStyle(headerTextStyle, cellPadding, columnArrangement, borderColor, borderStrokeWidth, headerBorderColor, dividerStyle)`。
- GPT 默认表格样式填充：`fuck-gpt/jadx-out/sources/defpackage/mmu.java:121-157`，`cellPadding` 缺省走 `ijy.b`，`borderStrokeWidth` 缺省走 `1.0f`。
- GPT 默认表格 token：`fuck-gpt/jadx-out/sources/defpackage/ijy.java:6-10`，`headerTextStyle` 默认字重来自 `a5i.t0`，`cellPadding = cet.n(8)`，默认 `borderColor = xl9.l`，默认 `columnArrangement = ao9.a`，默认 `dividerStyle = wmf.v`。
- GPT ChatGPT 样式覆盖：`fuck-gpt/jadx-out/sources/defpackage/zao.java:35` 构造 `qjy(null, null, obj, borderColor, 1.0f, headerBorderColor, k850.s0)`，未覆盖 `cellPadding`，所以继续使用默认 `8`。
- GPT 默认列布局：`fuck-gpt/apktool-out/smali_classes5/ijy.smali:1546-1633` 读取 `qjy.c`，当 `columnArrangement` 是默认 `Lao9` 时构造 `hm00(borderStrokeWidthPx)`。
- GPT 横向滚动只属于另一种列布局：`fuck-gpt/apktool-out/smali_classes5/ijy.smali:1646-1678` 遇到 `Lzn9` 才构造 `ns(200dpPx)`，`ijy.smali:1755-1788` 也只有 `Lzn9` 分支才调用 `kyf.R(...)` 和 `kyf.F(...)`。
- GPT 默认等宽列公式：`fuck-gpt/apktool-out/smali_classes5/hm00.smali:74-120` 按 `(maxWidthPx - borderStrokeWidthPx * (columnCount + 1)) / columnCount` 计算，和 `10px` 取最大值后 `roundToInt`。
- GPT ChatGPT 表格 dividerStyle：`fuck-gpt/jadx-out/sources/defpackage/zao.java:33-35` 使用 `k850.s0` 构造 `qjy(..., borderColor, 1dp, headerBorderColor, k850.s0)`。
- GPT 表格绘制颜色传递：`fuck-gpt/apktool-out/smali_classes5/ijy.smali:1824` 读取 `qjy.d` 作为 `borderColor`，`ijy.smali:1905-1909` 构造 `Letf(borderColor, qjy, 8)`。
- GPT headerBorderColor 兜底：`fuck-gpt/apktool-out/smali_classes5/etf.smali:691-718` 读取 `qjy.f`，存在时用 headerBorderColor，缺失时回退到 borderColor，再读取 `qjy.g` dividerStyle。
- GPT 表格实际绘制入口：`fuck-gpt/apktool-out/smali_classes5/etf.smali:722-726` 构造 `hjy(rows, dividerStyle, columnOffsets, headerBorderColorOrBorderColor, borderColor, borderStrokeWidth)`。
- GPT 非默认 dividerStyle 的横线语义：`fuck-gpt/apktool-out/smali_classes5/hjy.smali:160-186` 先判断 `dividerStyle instance-of Lwmf`，非 `wmf` 时仅在横线索引大于 `0` 且小于最后索引时绘制，跳过最外上边框和最外下边框。
- GPT header 底线颜色：`fuck-gpt/apktool-out/smali_classes5/hjy.smali:191` 在横线索引为 `1` 时使用 headerBorderColor，其余内部横线使用 borderColor。
- GPT 竖线语义：`fuck-gpt/apktool-out/smali_classes5/hjy.smali:351` 只有 `dividerStyle instance-of Lwmf` 时才进入竖线绘制。ChatGPT 样式是 `k850.s0`，所以不画竖线。

结论：GPT 表格单元格 padding 证据值为 `8`，表格边线宽证据值为 `1dp`，默认列布局是等宽铺满有限宽度，默认不额外套横向滚动。ChatGPT 表格样式使用非 `wmf` 的 `k850.s0`，所以绘制 header 底线和 body 内部横线，跳过最外上下边框且不画竖线。EveryTalk 已把表格单元格 padding、表格行分隔线宽、默认等宽列公式和 `k850.s0` 分隔线语义映射到 `ChatMarkdownTextStyle.TABLE_CELL_PADDING_DP = 8f`、`TABLE_BORDER_STROKE_WIDTH_DP = 1f`、`TableUtils.calculateChatGptEqualColumnWidthPx(...)` 和 `TableRenderer`。颜色 token 仍为 EveryTalk Material outline 本地映射，未声称等同 GPT 主题 token。

### 11. 待补证据

- Markdown AST/domain model 仍未从 `StreamBlocksRenderer.kt` 抽成独立解析层；当前是主聊天原生路径优先，不是完整替代所有 Markdown 入口。
- GPT 主题色 token 尚未全部逆向到 EveryTalk，例如表格颜色仍使用 EveryTalk Material outline 本地映射。

## 验证命令

```powershell
cd C:\Users\33039\Desktop\EveryTalk\app1
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.assistant prose container follows chatgpt padding evidence"
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.heading relative sizes follow chatgpt heading style evidence" --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.level four heading uses chatgpt bold eighteen sp scale" --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native heading text spec follows chatgpt heading style evidence"
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.markdown.MarkwonCacheTest.heading alpha and italic follow chatgpt heading style evidence" --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native heading text spec follows chatgpt heading style evidence"
.\gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.components.streaming.StreamBlocksRendererRoutingTest.native block spacing uses chatgpt paragraph spacing token"
.\gradlew :app:compileDebugKotlin
```
