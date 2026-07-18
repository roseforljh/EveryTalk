# 自动置顶滚动系统

## 概述

EveryTalk 的聊天置顶滚动由统一的 Compose top-anchor reserve engine 负责。文本聊天和图像生成共用 `com.android.everytalk.ui.topanchor` 包，不再各自维护 `pinnedUserMessageId`、`dynamicBottomPadding`、`grokScrollCompleted` 等页面级状态。

目标行为：

1. 第一条用户消息只记录锚点位置。
2. 第二条及之后的用户消息进入 top-anchor runtime。
3. 用户气泡保持在顶部阅读锚点，assistant、loading、status、reasoning、图片结果在下方展开。
4. 非用户触发的自动跳底在 runtime 存活时被拦截。
5. 用户点击回到底部或手动拖动列表会清理 runtime。
6. 流式结束后进入 Retained 阶段，reserve 和 correction loop 继续保留，直到用户动作、会话切换、下一轮置顶或锚点消失。

## 核心文件

| 文件 | 职责 |
| --- | --- |
| `ui/topanchor/TopAnchorModels.kt` | phase、turn、runtime、item role、config |
| `ui/topanchor/TopAnchorTurnPolicy.kt` | 根据 last-sent user id 和 item role 解析 active turn |
| `ui/topanchor/TopAnchorItemMapper.kt` | 将 `ChatListItem` 映射为 top-anchor role |
| `ui/topanchor/TopAnchorGeometry.kt` | 普通和超高用户气泡的锚点 Y 计算 |
| `ui/topanchor/TopAnchorReservePolicy.kt` | reserve 增长与 shrink 策略 |
| `ui/topanchor/TopAnchorBottomScrollGate.kt` | 自动跳底拦截策略 |
| `ui/topanchor/TopAnchorLazyListBridge.kt` | LazyList layout snapshot |
| `ui/topanchor/TopAnchorReserveEngine.kt` | Compose runtime、initial snap、correction loop、retained reserve |
| `ChatScrollStateManager.kt` | 注册 runtime clearer，统一处理用户跳底和手动拖动 |
| `ChatMessagesList.kt` | 文本聊天接入 engine |
| `ImageGenerationMessagesList.kt` | 图像生成接入同一个 engine |

## 状态机

```text
Idle
  -> AnchorRecorded
  -> InitialSnap
  -> AnchoredRunning
  -> Retained
  -> Idle
```

`Retained` 是流式结束后的保持阶段。它保留 `retainedTurn`、`reservePx`、bottom-scroll suppression 和 correction loop，因此图片 decode、Markdown 二次排版、思考框折叠仍会继续校正用户气泡位置。

`TopAnchorRuntimeState.currentTurn` 始终返回 `activeTurn ?: retainedTurn`。页面和 engine 都使用 `runtime.hasRuntime` 判断是否继续运行，不能只看 `activeTurn`。

## Reserve 规则

增长只处理正 drift：

```text
if driftPx > 0:
  missing = driftPx - max(0, consumedScroll)
  reservePx = clamp(reservePx + missing, 0, viewportHeight)
```

负 drift 不增长底部 spacer，因为尾部 reserve 无法修正“锚点高于目标”的方向。

shrink 在 engine correction loop 内执行：

```text
visibleGap = viewportEnd - trailingRealItemBottom - afterContentPadding
reservePx = min(reservePx, max(0, visibleGap))
```

## Bottom Scroll Gate

`ChatScrollStateManager` 提供：

```kotlin
updateTopAnchorBottomScrollSuppression(Boolean)
setTopAnchorRuntimeClearer((() -> Unit)?)
```

规则：

1. 用户动作总是允许。
2. runtime suppression 激活时，非用户自动跳底被拦截。
3. 用户点击回到底部时，允许跳底并调用同一个 clearer。
4. `NestedScrollSource.UserInput` 手动拖动时，也调用同一个 clearer。
5. 锚点消息消失后页面会清理 runtime，suppression 随 runtime 状态更新为 false。

## Item Mapper

文本和图像模式共用 `mapChatItemsToTopAnchorItems(...)`：

| `ChatListItem` | Role |
| --- | --- |
| `UserMessage` | `User` |
| `LoadingBubblePlaceholder(role = User)` | `User` |
| `AiMessage`、`AiMessageStreaming`、`AiMessageCode`、`AiMessageCodeStreaming`、`AiMessageReasoning`、`AiMessageFooter` | `AssistantTarget` |
| `LoadingIndicator`、`LoadingBubblePlaceholder(role = Assistant)` | `LoadingTarget` |
| `StatusIndicator` | `StatusTarget` |
| assistant error | `AssistantTarget` |
| user error | `User` |
| `SystemMessage` | `NonTarget` |

## 验证命令

```bash
cd app1
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.topanchor.*"
./gradlew :app:testDebugUnitTest --tests "*ChatScreenScrollSessionTest*"
./gradlew :app:testDebugUnitTest --tests "com.android.everytalk.ui.topanchor.TopAnchorReserveEngineComposeTest"
./gradlew :app:compileDebugKotlin
```
