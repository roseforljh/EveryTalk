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

## 置顶算法详解

### 阶段一：捕获首条气泡位置 (firstBubbleScreenY)

当会话中只有一条用户消息时（`currentUserMessageIndices.size == 1`），系统捕获该气泡在屏幕上的 Y 坐标：

```kotlin
firstBubbleScreenY = firstItem.offset - listState.layoutInfo.viewportStartOffset
```

这个值代表"用户气泡应该出现的屏幕位置"，后续所有新消息都会被滚动到这个位置。

### 阶段二：置顶滚动

当发送第 2+ 条消息时：

1. **设置动态 padding**：`dynamicBottomPaddingTarget = viewportHeight`
   - 在 LazyColumn 底部插入一个 Spacer，提供足够的滚动空间
   - 没有这个 padding，LazyColumn 无法将底部的消息滚动到屏幕顶部区域

2. **等待布局**：`delay(100)` 让 padding 生效

3. **粗滚动**：`listState.scrollToItem(lastUserIndex, scrollOffset = -targetScreenY)`
   - 将目标消息大致滚动到 targetScreenY 位置

4. **精修正**：测量实际位置与目标的偏差，用动画修正
   ```kotlin
   val correction = actualY - targetScreenY
   listState.animateScrollBy(correction.toFloat(), tween(400ms))
   ```

### 阶段三：动态缩小 padding

置顶完成后（800ms delay），启动 snapshotFlow 监测：

```kotlin
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

随着 AI 内容增长，padding 自动缩小，用户无法滚动到空白区域。

### 阶段四：清除 padding

API 调用完成后：

```kotlin
LaunchedEffect(isApiCalling) {
    if (!isApiCalling && dynamicBottomPaddingTarget > 0.dp) {
        delay(300)
        dynamicBottomPaddingTarget = 0.dp
    }
}
```

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
    │       │   ├─ 设置 dynamicBottomPaddingTarget = viewportHeight
    │       │   ├─ delay(100)
    │       │   ├─ scrollToItem(lastUserIndex, -targetScreenY)
    │       │   ├─ delay(50)
    │       │   └─ animateScrollBy(correction) 精修正
    │       │
    │       └─ consumeLastSentUserMessageId()
    │
    ├─► LaunchedEffect(isApiCalling = true)
    │       ├─ delay(800) 等待滚动完成
    │       └─ snapshotFlow 动态缩小 padding
    │
    └─► API 完成 → LaunchedEffect(isApiCalling = false)
            └─ delay(300) → dynamicBottomPaddingTarget = 0
```

## 关键设计决策

| 决策 | 原因 |
|------|------|
| padding 用 `viewportHeight` 而非 `viewportHeight * 2` | 1x 足够置顶，2x 产生过多空白 |
| 动态缩小延迟 800ms | 等待置顶滚动动画完成，避免提前缩小导致滚动失败 |
| 缩小逻辑 key 只用 `isApiCalling` | 避免 target 变化重启 LaunchedEffect 导致 delay 重置 |
| `animateDpAsState` 做 padding 过渡 | 500ms tween 动画，避免 padding 突变导致视觉跳动 |
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
