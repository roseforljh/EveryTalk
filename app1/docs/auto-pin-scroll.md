# 自动置顶滚动系统 (Auto-Pin Scroll)

## 概述

EveryTalk 的自动置顶系统实现了类似 GPT/Grok 风格的聊天滚动体验：用户发送消息后，最新的用户气泡会被"钉"在屏幕上与第一条消息相同的 Y 位置，AI 回复在其下方展开。文本模式和图像生成模式共用同一套核心逻辑。

## 核心组件

### 1. 状态持有层

| 文件 | 字段 | 作用 |
|------|------|------|
| `ViewModelStateHolder.kt` | `_lastSentUserMessageId` | 文本模式：最新发送的用户消息 ID |
| `ViewModelStateHolder.kt` | `_lastSentImageUserMessageId` | 图像模式：最新发送的用户消息 ID |
| `AppViewModel.kt` | `lastSentUserMessageId` / `lastSentImageUserMessageId` | 暴露给 UI 的 StateFlow |
| `AppViewModel.kt` | `consumeLastSentUserMessageId()` / `consumeLastSentImageUserMessageId()` | 消费信号，防止重复触发 |

### 2. 信号发射

`MessageSender.kt` 在消息添加到列表后立即设置对应的 ID：

```kotlin
// 文本模式
stateHolder._lastSentUserMessageId.value = newUserMessageForUi.id

// 图像模式
stateHolder._lastSentImageUserMessageId.value = newUserMessageForUi.id
```

`RegenerateController.kt` 在重新生成时也会设置 `_lastSentUserMessageId`。

### 3. UI 层置顶逻辑

位于 `ChatMessagesList.kt`（文本）和 `ImageGenerationMessagesList.kt`（图像）。

图像模式还有一个关键约束：生成期间和 pinned reserve 存活期间，不能有任何外部自动滚动覆盖置顶状态。`ImageGenerationMessagesList.kt` 会先用 `shouldDispatchImageLoadedToBottomScroller(...)` 过滤图片加载回调，只有不在生成中、不在 pinned reserve 中，并且列表确实在底部时，才允许外层 `onImageLoaded -> jumpToBottom()`。

## 置顶算法详解

### 阶段一：捕获首条气泡位置 (firstBubbleScreenY)

当会话中只有一条用户消息时（`currentUserMessageIndices.size == 1`），系统捕获该气泡在屏幕上的 Y 坐标：

```kotlin
firstBubbleScreenY = firstItem.offset - listState.layoutInfo.viewportStartOffset
```

这个值代表"用户气泡应该出现的屏幕位置"，后续所有新消息都会被滚动到这个位置。

### 阶段二：置顶滚动

当发送第 2+ 条消息时：

1. **置顶动画期间临时冻结用户滚动**：`grokScrollCompleted = false`
   - LazyColumn 通过 `shouldEnableUserScrollForPinnedUserBubble(...)` 计算 `userScrollEnabled`
   - 仅在程序置顶动画执行期间冻结用户手势，动画完成后立即恢复用户滚动

2. **记录钉住目标**：`pinnedUserMessageId = sentId`
   - dynamic reserve 存活期间，帧级修正逻辑会持续检查这个消息是否偏离目标 Y

3. **设置动态 padding**：`dynamicBottomPaddingImmediate = viewportHeight`，`dynamicBottomPaddingTarget = viewportHeight`
   - 在 LazyColumn 底部插入一个 Spacer，提供足够的滚动空间
   - 没有这个 padding，LazyColumn 无法将底部的消息滚动到屏幕顶部区域
   - 初始置顶使用 snap，非零 padding 快速动画，归零使用慢动画，避免 immediate/animated 切换造成跳变

4. **等待布局**：等待 LazyColumn 总 item 数包含 dynamic padding spacer

5. **粗滚动**：如果目标 item 不在可见区域，先 `animateScrollToItem(lastUserIndex, scrollOffset = 0)`
   - 先让目标消息进入可见区域，确保后续能测量真实 offset

6. **精修正**：测量实际位置与目标的偏差，用逐帧 `scrollBy` 修正
   ```kotlin
   val distancePx = startY - targetScreenY
   listState.scrollBy(current - previous)
   ```

7. **恢复用户滚动**：`finally { grokScrollCompleted = true }`
   - 后续用户可以继续拖动列表
   - 如果气泡被拖离锚点，帧级修正会把它拉回，而不是预先消费用户上滑

### 阶段三：帧级钉住修正

`pinnedUserMessageId` 不为空且置顶动画完成后启动持续修正。修正不依赖 `isApiCalling == true`，因为流式结束后 layout、图片加载、padding 动画仍可能让气泡短暂偏离锚点：

```kotlin
val item = visibleItemsInfo.firstOrNull { it.key == pinnedId } ?: continue
val currentY = item.offset - viewportStartOffset
val drift = currentY - targetY
if (abs(drift) > 1) {
    val consumed = listState.scrollBy(drift.toFloat())
}
```

作用：

- AI 内容变高、图片加载、loading 文案切换都会改变列表布局
- 一旦用户气泡偏离目标 Y，下一帧立即滚回去
- 如果滚动空间不够，会补充 dynamic padding，保证消息仍能被钉住
- layout version 同时包含 `totalItemsCount`、`firstVisibleItemIndex`、`firstVisibleItemScrollOffset`、可见 item 尺寸和 offset 总和
- 用户拖动造成的 `firstVisibleItemScrollOffset` 变化也会唤醒修正循环，避免“拖动后等下一次布局才回正”
- 不使用 `NestedScrollConnection` 预消费上滑，GPT 逆向证据没有证明存在这种输入拦截

### 阶段四：动态缩小 padding

置顶完成后，启动 snapshotFlow 监测：

```kotlin
snapshotFlow { grokScrollCompleted }.first { it }
snapshotFlow {
    val lastRealItem = visibleItemsInfo.lastOrNull { it.key != "dynamic_padding_spacer" }
    val gap = viewportEnd - lastRealItem.bottom - afterContentPadding
    gap.coerceAtLeast(0)
}.collect { gapPx ->
    if (newPadding < dynamicBottomPaddingTarget) {
        dynamicBottomPaddingTarget = newPadding
    }
}
```

随着 AI 内容增长，padding 自动缩小，用户无法滚动到空白区域。shrink flow 会等待 `grokScrollCompleted = true`，避免在置顶动画未完成时过早收缩导致目标消息无法到达锚点。

### 阶段五：清除 padding

同一会话内流式结束不会立即清空 transient reserve：

```kotlin
shouldClearTransientBottomReserveOnStreamChange(isApiCalling = false) == false
```

原因：如果 API 一结束就把底部 reserve 清零，用户气泡会失去“GPT 式底部余量 + 锚点回拉”的手感，出现底部空白瞬间消失的问题。

真正清空 transient reserve 的时机：

- `scrollSessionKey` 改变
- conversation 切换且当前不在 active streaming
- 新一轮置顶流程重新设置 reserve

## 图像模式专项修复要点

图像模式现在已补齐文字模式的最新置顶口径：

- 使用 `shouldEnableUserScrollForPinnedUserBubble(...)`，置顶动画完成后恢复用户滚动
- 使用 `shouldClearTransientBottomReserveOnStreamChange(...)`，同一会话内 stream 结束不立即清空 reserve
- 使用 `pinnedAnchorLayoutVersion(...)`，把 `firstVisibleItemScrollOffset` 和可见 item offset 纳入修正唤醒条件
- 使用 `restorePinnedBubbleAnchorForSession(...)`，切回会话时不恢复过期 runtime 锚点
- 帧级 drift 修正不再绑定 `isApiCalling == true`，stream 结束后的图片加载和 padding 动画也能继续回拉
- 图像模式 dynamic reserve 收缩逻辑对齐文本模式，按真实 visible gap 收缩，不额外保留 viewport 级大空白
- 图片 `onImageLoaded` 回调在 pinned reserve 存活时不再向外触发 `jumpToBottom()`，避免图片 decode 完成后覆盖置顶位置
- 图像重新回答保存历史时，已加载历史会话优先沿用当前 conversation id，避免重启后生成结果跑到新会话
- 不使用前置 `NestedScrollConnection` 消费用户上滑，允许手势产生轻微抖动，再由帧级校正拉回锚点

图像模式曾出现两类底部 reserve 问题：

1. 用户气泡已置顶但仍能向下滚出大量空白
2. 没有切换会话时，AI 图片生成完成后底部空白瞬间消失

根因不是单一滚动函数，而是多个机制叠加：

### 1. 最后一条 AI/Loading 被强行撑高

旧逻辑：

```kotlin
val shouldApplyMinHeight = isLastItem && chatItems.size >= 2
Modifier.heightIn(min = availableHeight * 0.85f)
```

案例：用户消息刚被钉到 85dp 位置，下面的 loading item 又被强制撑成 85% 屏幕高度。即使 dynamic padding 被缩小，列表本身仍有一大块真实内容高度，所以用户还能继续向下滚出空白感。

修复：

- 图像模式 AI 消息不再套 `heightIn(min = availableHeight * 0.85f)`
- 图像模式 LoadingIndicator 不再套 `heightIn(min = availableHeight * 0.85f)`
- LoadingIndicator 使用正常内容高度，只让 dynamic padding 负责置顶空间

### 2. 图片加载完成后自动跳底覆盖置顶

旧逻辑：

```kotlin
onImageLoaded = {
    if (scrollStateManager.isAtBottom.value) {
        scrollStateManager.jumpToBottom()
    }
}
```

案例：用户气泡刚被帧级逻辑钉住，AI 图片加载完成触发 `onImageLoaded`，如果此时底部检测认为还在 bottom，就会调用 `jumpToBottom()`，直接把置顶状态覆盖掉。

修复：

```kotlin
shouldDispatchImageLoadedToBottomScroller(
    isApiCalling = isApiCalling,
    isAtBottom = scrollStateManager.isAtBottom.value,
    hasPinnedUserMessage = pinnedUserMessageId != null,
    hasDynamicBottomReserve = dynamicBottomPaddingTarget > 0.dp
)
```

生成期间禁止图片加载事件触发跳底；生成结束后，如果 pinned reserve 仍存活，也继续禁止跳底。只有不在生成中、不在 pinned reserve 中，并且列表确实在底部时，才保留普通图片加载补偿。

### 3. 图像模式目标 Y 和 LazyColumn 实际 top padding 不一致

旧逻辑目标：

```kotlin
val topPaddingPx = with(density) { 8.dp.toPx().toInt() }
```

实际 LazyColumn：

```kotlin
contentPadding = PaddingValues(top = 85.dp)
```

案例：算法以 8dp 为置顶目标，布局实际从 85dp 开始，导致测量、修正、可滚动边界不一致。

修复：

```kotlin
val topPaddingPx = with(density) { 85.dp.toPx().toInt() }
```

图像模式必须保证 `topPaddingPx` 和 LazyColumn `contentPadding.top` 同源。

### 4. 图像模式不能额外发明 reserve 策略

图像生成的 AI item 通常比文本回复高很多。发送瞬间为了把底部用户消息顶到顶部，会先给 `viewportHeight` 级 reserve；如果图像模式自己额外保留 bounded reserve 或完全跳过 shrink，就会和文本模式不一致，产生页面乱跳或轻微滑动露出大量空白。

错误策略：

```kotlin
// pinned 时完全跳过 shrink，保留 viewportHeight 级大空白
// 或者强行限制到固定 96dp..240dp，和真实 layout gap 不一致
```

案例：屏幕高 900px，图像模式先插入 900px spacer。如果不按真实 gap shrink，用户轻轻一滑就会露出一大段空白；如果固定压到 240px，又会在生成中发生突兀收缩，表现为页面乱跳。

修复：

```kotlin
resolveDynamicBottomReserveForVisibleGap(
    currentReservePx = currentReservePx,
    visibleGapPx = gapPx,
    minPinnedReservePx = 0,
    maxPinnedReservePx = currentReservePx,
    hasPinnedUserMessage = pinnedUserMessageId != null,
)
```

当前策略：图像模式和文本模式一样按真实 visible gap 收缩。额外保留底部空白不在图像模式里硬编码，避免滚动系统出现两套手感。

### 5. 图像重新回答后重启跑到新会话

图像历史原本用“首条消息 ID”作为稳定会话 ID。重新回答会删除原用户消息并生成新的 user message ID，当前内存里因为 loaded index 还在，短时间看起来会更新当前会话；但保存历史时如果重新用新首条 user id 作为 stable id，重启后历史恢复会把它识别成另一条会话。

修复：

```kotlin
resolveHistoryExpectedStableConversationId(
    isImageGeneration = true,
    loadedHistoryIndex = loadedHistoryIndex,
    currentConversationId = currentId,
    stableIdFromMessages = stableConversationId(messagesToSave),
)
```

图像模式只要正在更新已加载历史项，就优先沿用当前 conversation id；只有新图像会话首次入库时，才迁移到首条 user message id。

## Session 保护机制

### 问题背景

新会话的 `conversationId` 会从临时 ID（如 `new_text_xxx`）变为稳定 ID（如 `user_xxx`）。如果 `scrollSessionKey` 跟随变化，会触发 `LazyListState` 重建，导致置顶状态丢失。

### 解决方案

`ChatScreen.kt` 中的 `shouldPreserveScrollSessionOnConversationIdChange`：

```kotlin
// conversationId 未变时，保留当前 session（防止 messages.size 变化触发虚假重置）
if (previousConversationId == newConversationId) {
    return true
}
// conversationId 变化但属于 ID 稳定化（temp → stable），也保留
val derivedStableConversationId = messages.firstOrNull { it.sender == Sender.User }?.id
return derivedStableConversationId == newConversationId
```

## 位置记忆与锚点恢复

文本模式和图像模式都会通过 `ConversationScrollState` 缓存 `firstBubbleScreenY`，但这个值只作为 active pinned runtime 的临时锚点参考：

- 首次发送时捕获首条用户气泡的屏幕 Y
- 发送第 2+ 条消息时复用该 Y 作为置顶锚点
- 切换会话或重新进入 scroll session 时，不盲目恢复历史保存的 `firstBubbleScreenY`
- 只有仍处于 active pinned runtime 时，`restorePinnedBubbleAnchorForSession(...)` 才允许恢复锚点

案例：A 会话首次发送时锚点是 184px；切到 B 再切回 A 后，输入框高度、状态栏、列表恢复位置可能已经变化。如果继续用 184px，会导致新一轮置顶偏移。因此切回会话时默认重算锚点。

## 时序图

```
用户点击发送
    │
    ├─► MessageSender 设置 lastSent 用户消息 ID
    │
    ├─► LaunchedEffect(lastSent 用户消息 ID) 触发
    │       │
    │       ├─ size == 1? → 捕获 firstBubbleScreenY，return
    │       │
    │       ├─ size > 1:
    │       │   ├─ grokScrollCompleted = false，禁止用户手动滚动
    │       │   ├─ pinnedUserMessageId = sentId，记录钉住目标
    │       │   ├─ 设置 dynamicBottomPaddingImmediate/Target = viewportHeight
    │       │   ├─ 等待 dynamic padding spacer 进入布局
    │       │   ├─ 如目标不可见，animateScrollToItem(lastUserIndex, 0)
    │       │   ├─ 逐帧 scrollBy 修正到 targetScreenY
    │       │   ├─ 立即缩小多余 padding
    │       │   └─ finally 设置 grokScrollCompleted = true
    │       │
    │       └─ consumeLastSent 用户消息 ID
    │
    ├─► LaunchedEffect(isApiCalling = true)
    │       ├─ 等待 grokScrollCompleted = true
    │       └─ snapshotFlow 动态缩小 padding
    │
    ├─► LaunchedEffect(pinnedUserMessageId)
    │       └─ dynamic reserve 存活期间逐帧修正用户气泡 drift
    │
    └─► API 完成 → LaunchedEffect(isApiCalling = false)
            ├─ delay(300) → 清空 dynamicBottomPadding
            ├─ pinnedUserMessageId = null
            └─ grokScrollCompleted = true
```

## 关键设计决策

| 决策 | 原因 |
|------|------|
| padding 用 `viewportHeight` 而非 `viewportHeight * 2` | 1x 足够置顶，2x 产生过多空白 |
| `grokScrollCompleted` 控制 `userScrollEnabled` | 置顶滚动期间禁止用户手势抢滚动状态 |
| `pinnedUserMessageId` 做帧级修正 | dynamic reserve 存活期间持续把用户气泡钉回目标 Y |
| shrink flow 等待 `grokScrollCompleted` | 避免 padding 过早缩小导致置顶失败 |
| stream 结束不立即清空 reserve | 保留 GPT 式余量和回拉手感，避免底部空白瞬间消失 |
| layout version 包含 scroll offset | 用户拖动也能唤醒修正循环，不必等下一次 item 尺寸变化 |
| 不预消费用户上滑 | 逆向证据不支持 GPT 有前置手势消费，采用“允许抖动 + 帧级回拉” |
| 图像模式不对最后一条 AI/Loading 加 0.85 屏高 | 避免真实内容高度制造可下滚空白 |
| 图像生成中禁用 `onImageLoaded -> jumpToBottom()` | 防止图片加载完成覆盖置顶状态 |
| 图像 pinned reserve 存活时禁用 image loaded 跳底 | 防止生成结束后的图片 decode 回调把用户气泡拉回底部 |
| 图像 reserve shrink 对齐文本模式 | 避免额外 bounded 策略导致生成中页面乱跳 |
| 图像历史保存已加载会话优先沿用 current id | 防止重新回答更换首条 user id 后，重启恢复成新会话 |
| 图像模式 topPaddingPx 使用 85dp | 必须和 LazyColumn `contentPadding.top` 一致 |
| session 保护返回 `true` 当 ID 相同 | 防止 `messages.size` 变化触发虚假 session 重置 |

## 涉及文件清单

| 文件 | 职责 |
|------|------|
| `ChatMessagesList.kt` | 文本模式置顶逻辑主体 |
| `ImageGenerationMessagesList.kt` | 图像模式置顶逻辑主体 |
| `ChatScrollStateManager.kt` | 滚动状态管理、nestedScroll、scrollItemToTop |
| `ChatScreen.kt` | scrollSessionKey 管理、session 保护 |
| `ImageGenerationScreen.kt` | 图像模式页面容器 |
| `ViewModelStateHolder.kt` | 状态持有：lastSentUserMessageId / lastSentImageUserMessageId |
| `MessageSender.kt` | 发送消息时设置置顶信号 |
| `RegenerateController.kt` | 重新生成时设置置顶信号 |
| `AppViewModel.kt` | 暴露 StateFlow 和 consume 方法 |
