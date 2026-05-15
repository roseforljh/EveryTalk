# 自动置顶滚动系统 (Auto-Pin Scroll)

## 概述

EveryTalk 的自动置顶系统实现了类似 Grok/Intercom 风格的聊天滚动体验：用户发送消息后，最新的用户气泡会被"钉"在屏幕上与第一条消息相同的 Y 位置，AI 回复在其下方展开。文本模式和图像生成模式共用同一套核心逻辑。

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

图像模式还有一个关键约束：生成期间不能有任何外部自动滚动覆盖置顶状态。`ImageGenerationScreen.kt` 的 `onImageLoaded` 必须在 `!isApiCalling` 时才允许 `jumpToBottom()`，否则图片加载完成会把刚钉住的用户消息重新拉到底部。

## 置顶算法详解

### 阶段一：捕获首条气泡位置 (firstBubbleScreenY)

当会话中只有一条用户消息时（`currentUserMessageIndices.size == 1`），系统捕获该气泡在屏幕上的 Y 坐标：

```kotlin
firstBubbleScreenY = firstItem.offset - listState.layoutInfo.viewportStartOffset
```

这个值代表"用户气泡应该出现的屏幕位置"，后续所有新消息都会被滚动到这个位置。

### 阶段二：置顶滚动

当发送第 2+ 条消息时：

1. **禁止用户手动滚动**：`grokScrollCompleted = false`
   - LazyColumn 使用 `userScrollEnabled = grokScrollCompleted`
   - 置顶动画执行期间，用户滑动不会和程序滚动抢状态

2. **记录钉住目标**：`pinnedUserMessageId = sentId`
   - API 调用期间，帧级修正逻辑会持续检查这个消息是否偏离目标 Y

3. **设置动态 padding**：`dynamicBottomPaddingImmediate = viewportHeight`，`dynamicBottomPaddingTarget = viewportHeight`
   - 在 LazyColumn 底部插入一个 Spacer，提供足够的滚动空间
   - 没有这个 padding，LazyColumn 无法将底部的消息滚动到屏幕顶部区域
   - API 调用期间使用 immediate padding，避免动画延迟导致置顶时机不稳定

4. **等待布局**：等待 LazyColumn 总 item 数包含 dynamic padding spacer

5. **粗滚动**：如果目标 item 不在可见区域，先 `animateScrollToItem(lastUserIndex, scrollOffset = 0)`
   - 先让目标消息进入可见区域，确保后续能测量真实 offset

6. **精修正**：测量实际位置与目标的偏差，用逐帧 `scrollBy` 修正
   ```kotlin
   val distancePx = startY - targetScreenY
   listState.scrollBy(current - previous)
   ```

7. **恢复用户滚动**：`finally { grokScrollCompleted = true }`

### 阶段三：帧级钉住修正

API 调用期间，`pinnedUserMessageId` 不为空时启动持续修正：

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

随着 AI 内容增长，padding 自动缩小，用户无法滚动到空白区域。`pinnedUserMessageId != null` 时普通 shrink flow 不主动缩小 padding，避免和帧级钉住修正抢状态。

### 阶段五：清除 padding

API 调用完成后：

```kotlin
LaunchedEffect(isApiCalling) {
    if (!isApiCalling && dynamicBottomPaddingTarget > 0.dp) {
        delay(300)
        dynamicBottomPaddingTarget = 0.dp
        dynamicBottomPaddingImmediate = 0.dp
    }
    pinnedUserMessageId = null
    grokScrollCompleted = true
}
```

## 图像模式专项修复要点

图像模式曾出现“用户气泡已置顶但仍能向下滚出大量空白”的问题，根因不是单一滚动函数，而是三个机制叠加：

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
onImageLoaded = {
    if (!isApiCalling && scrollStateManager.isAtBottom.value) {
        scrollStateManager.jumpToBottom()
    }
}
```

生成期间禁止图片加载事件触发跳底，生成结束后保留普通图片加载补偿。

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

## 位置记忆（文本模式独有）

文本模式通过 `ConversationScrollState` 持久化 `firstBubbleScreenY`：

- 切换会话时保存当前位置
- 切回时恢复，确保置顶位置一致

## 时序图

```
用户点击发送
    │
    ├─► MessageSender 设置 _lastSentUserMessageId
    │
    ├─► LaunchedEffect(lastSentUserMessageId) 触发
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
    │       └─ consumeLastSentUserMessageId()
    │
    ├─► LaunchedEffect(isApiCalling = true)
    │       ├─ 等待 grokScrollCompleted = true
    │       └─ snapshotFlow 动态缩小 padding
    │
    ├─► LaunchedEffect(pinnedUserMessageId)
    │       └─ API 调用期间逐帧修正用户气泡 drift
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
| `pinnedUserMessageId` 做帧级修正 | API 调用期间持续把用户气泡钉回目标 Y |
| shrink flow 等待 `grokScrollCompleted` | 避免 padding 过早缩小导致置顶失败 |
| 图像模式不对最后一条 AI/Loading 加 0.85 屏高 | 避免真实内容高度制造可下滚空白 |
| 图像生成中禁用 `onImageLoaded -> jumpToBottom()` | 防止图片加载完成覆盖置顶状态 |
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
