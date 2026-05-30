# Regenerate Preserve Follow-up Implementation Plan

> **For Codex:** 按本计划逐项执行。仓库规则要求不要主动 git commit，不要使用 git checkout，Kotlin/Android 验证使用 Gradle。

**Goal:** 修复重新回答较早问题时误删后续问答的问题。

**Architecture:** 当前 `RegenerateController` 从基准用户消息开始删除整条后续分支，导致 `user-1, ai-1, user-2, ai-2` 中重答 `ai-1` 后只剩 `user-1`。修复方案改为只移除基准用户消息后、下一条用户消息前的旧回答，并让 `MessageSender` 在重新生成时复用原用户消息位置，`ApiHandler` 把新 AI 占位消息插回该用户消息后面。

**Tech Stack:** Kotlin 2.2.10, Android ViewModel, SnapshotStateList, Coroutines, JUnit 4, MockK, Gradle。

---

### Task 1: 改回归测试，锁定真实用户场景

**Files:**
- Modify: `app/src/test/java/com/android/everytalk/statecontroller/controller/conversation/RegenerateControllerTest.kt`

**Step 1: 替换旧的错误预期测试**

把测试 `text regenerate truncates entire branch after base user message` 改名并改断言：

```kotlin
@Test
fun `text regenerate preserves later turns when regenerating earlier answer`() = scope.runTest {
    val stateHolder = stateHolderWithTextConfig()
    stateHolder.setCurrentConversationId("user-1")
    stateHolder._loadedHistoryIndex.value = 0
    stateHolder.messages.addAll(
        listOf(
            Message(id = "user-1", text = "第一问", sender = Sender.User),
            Message(id = "ai-1", text = "第一答", sender = Sender.AI),
            Message(id = "user-2", text = "第二问", sender = Sender.User),
            Message(id = "ai-2", text = "第二答", sender = Sender.AI),
        )
    )

    val sentLatch = CountDownLatch(1)
    val controller = createController(stateHolder) { text, _, _, isImageGeneration, manualMessageId ->
        val target = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
        val existingIndex = target.indexOfFirst { it.id == manualMessageId }
        val userMessage = Message(id = manualMessageId ?: "missing", text = text, sender = Sender.User)
        if (existingIndex >= 0) {
            target[existingIndex] = userMessage
            target.add(existingIndex + 1, Message(id = "new-ai-1", text = "新第一答", sender = Sender.AI))
        } else {
            target.add(userMessage)
        }
        sentLatch.countDown()
    }

    controller.regenerateFrom(stateHolder.messages[1], isImageGeneration = false)
    advanceUntilIdle()
    assertTrue(sentLatch.await(1, TimeUnit.SECONDS))
    advanceUntilIdle()

    assertEquals(listOf("user-1", "new-ai-1", "user-2", "ai-2"), stateHolder.messages.map { it.id })
}
```

**Step 2: 新增纯函数测试**

验证收集删除范围只覆盖旧回答：

```kotlin
@Test
fun `collect regeneration branch removes only answers before next user message`() {
    val messages = listOf(
        Message(id = "user-1", text = "第一问", sender = Sender.User),
        Message(id = "ai-1", text = "第一答", sender = Sender.AI),
        Message(id = "user-2", text = "第二问", sender = Sender.User),
        Message(id = "ai-2", text = "第二答", sender = Sender.AI),
    )

    val branch = collectRegenerationBranch(messages, userMessageIndex = 0)

    assertEquals(listOf("ai-1"), branch.map { it.id })
}
```

**Step 3: 运行失败用例**

Run:

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.controller.conversation.RegenerateControllerTest"
```

Expected: 上面两个测试至少一个失败，证明当前实现仍在删除后续问答。

---

### Task 2: 缩小 RegenerateController 的删除范围

**Files:**
- Modify: `app/src/main/java/com/android/everytalk/statecontroller/controller/conversation/RegenerateController.kt`

**Step 1: 修改 `collectRegenerationBranch`**

把当前实现：

```kotlin
internal fun collectRegenerationBranch(messages: List<Message>, userMessageIndex: Int): List<Message> =
    if (userMessageIndex in messages.indices) messages.drop(userMessageIndex) else emptyList()
```

改为：

```kotlin
internal fun collectRegenerationBranch(messages: List<Message>, userMessageIndex: Int): List<Message> {
    if (userMessageIndex !in messages.indices) return emptyList()
    return messages
        .drop(userMessageIndex + 1)
        .takeWhile { it.sender != Sender.User }
}
```

**Step 2: 保留现有删除调用**

`messagesToRemove` 继续传给：

```kotlin
val messagesForMediaCleanup = filterRegenerationMediaCleanupMessages(...)
listRef.removeAll(messagesToRemove.toSet())
```

因为 `messagesToRemove` 已不包含基准用户消息，`filterRegenerationMediaCleanupMessages` 仍可保留，减少改动面。

**Step 3: 运行控制器测试**

Run:

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.controller.conversation.RegenerateControllerTest"
```

Expected: 仍可能失败，因为新回答还会被追加到末尾。继续 Task 3。

---

### Task 3: 让 MessageSender 复用原用户消息位置

**Files:**
- Modify: `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt`

**Step 1: 定位用户消息写入位置**

修改 `sendMessage(...)` 中创建 `newUserMessageForUi` 后的主线程写入逻辑。当前逻辑直接：

```kotlin
stateHolder.messages.add(newUserMessageForUi)
```

**Step 2: 增加复用原位置逻辑**

在 `withContext(Dispatchers.Main.immediate)` 内先取目标列表：

```kotlin
val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
val existingRegenerationIndex = if (isFromRegeneration && manualMessageId != null) {
    messageList.indexOfFirst { it.id == manualMessageId }
} else {
    -1
}
```

再把文本模式和图像模式的 `add` 改为同一套写入：

```kotlin
if (existingRegenerationIndex >= 0) {
    messageList[existingRegenerationIndex] = newUserMessageForUi
} else {
    messageList.add(newUserMessageForUi)
}
if (isImageGeneration) {
    stateHolder._lastSentImageUserMessageId.value = newUserMessageForUi.id
} else {
    stateHolder._lastSentUserMessageId.value = newUserMessageForUi.id
    stateHolder.persistPendingParamsIfNeeded(isImageGeneration = false)
}
```

**Step 3: 保持历史请求上下文正确**

不改 `historyEndIndex`：

```kotlin
val historyEndIndex = messagesInChatUiSnapshot.indexOfFirst { it.id == newUserMessageForUi.id }
```

复用原 ID 后，`historyEndIndex` 会指向原问题位置，请求只带该问题之前的上下文。

---

### Task 4: 让新 AI 回答插回原问题后面

**Files:**
- Modify: `app/src/main/java/com/android/everytalk/statecontroller/ApiHandler.kt`
- Modify: `app/src/main/java/com/android/everytalk/statecontroller/MessageSender.kt`

**Step 1: 给预创建 AI 消息增加插入点**

修改 `ApiHandler.prepareStreamingAiMessage(...)` 签名：

```kotlin
fun prepareStreamingAiMessage(
    modelName: String,
    providerName: String,
    isImageGeneration: Boolean = false,
    onNewAiMessageAdded: () -> Unit = {},
    afterUserMessageId: String? = null,
): String
```

**Step 2: 插入到用户消息后面**

把 `messageList.add(newAiMessage)` 改为：

```kotlin
val insertIndex = afterUserMessageId
    ?.let { id -> messageList.indexOfFirst { it.id == id } }
    ?.takeIf { it >= 0 }
    ?.plus(1)

if (insertIndex != null && insertIndex <= messageList.size) {
    messageList.add(insertIndex, newAiMessage)
} else {
    messageList.add(newAiMessage)
}
```

**Step 3: 使用已有 `afterUserMessageId` 修复非预创建路径**

在 `ApiHandler.streamChatResponse(...)` 非 `preCreatedAiMessageId` 分支中，同样把：

```kotlin
messageList.add(newAiMessage)
```

改为按 `afterUserMessageId` 插入，逻辑同上。

**Step 4: MessageSender 传入插入点**

修改 `MessageSender.kt` 中预创建调用：

```kotlin
apiHandler.prepareStreamingAiMessage(
    modelName = currentConfig.model,
    providerName = currentConfig.provider,
    isImageGeneration = false,
    afterUserMessageId = newUserMessageForUi.id,
)
```

正常发送时用户消息在末尾，插入效果仍等同追加。重新回答较早问题时，新 AI 回答会回到该问题后面。

---

### Task 5: 扩展验证覆盖

**Files:**
- Modify: `app/src/test/java/com/android/everytalk/statecontroller/controller/conversation/RegenerateControllerTest.kt`
- Optional Modify: `app/src/test/java/com/android/everytalk/statecontroller/ApiHandlerTest.kt`，仅当现有文件已覆盖 `ApiHandler`

**Step 1: 保留问题2不消失的回归测试**

确认最终顺序：

```text
user-1, new-ai-1, user-2, ai-2
```

**Step 2: 保留重答问题2的行为**

新增或调整测试：

```kotlin
@Test
fun `text regenerate latest answer keeps previous turns`() = scope.runTest {
    val stateHolder = stateHolderWithTextConfig()
    stateHolder.messages.addAll(
        listOf(
            Message(id = "user-1", text = "第一问", sender = Sender.User),
            Message(id = "ai-1", text = "第一答", sender = Sender.AI),
            Message(id = "user-2", text = "第二问", sender = Sender.User),
            Message(id = "ai-2", text = "第二答", sender = Sender.AI),
        )
    )

    val branch = collectRegenerationBranch(stateHolder.messages, userMessageIndex = 2)

    assertEquals(listOf("ai-2"), branch.map { it.id })
}
```

**Step 3: 验证旧 AI 媒体会清理，后续消息媒体不清理**

用 `onDeleteMediaFor` 捕获传入 ID：

```kotlin
val deletedIds = mutableListOf<String>()
val controller = createController(
    stateHolder,
    onDeleteMediaFor = { lists -> deletedIds += lists.flatten().map { it.id } },
) { _, _, _, _, _ -> sentLatch.countDown() }
```

断言：

```kotlin
assertTrue(deletedIds.contains("ai-1"))
assertFalse(deletedIds.contains("user-2"))
assertFalse(deletedIds.contains("ai-2"))
```

---

### Task 6: 运行验证

**Files:**
- No file changes

**Step 1: 跑最小相关测试**

Run:

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "com.android.everytalk.statecontroller.controller.conversation.RegenerateControllerTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

**Step 2: 跑快速编译**

Run:

```powershell
& ".\gradlew.bat" :app:compileDebugKotlin
```

Expected:

```text
BUILD SUCCESSFUL
```

**Step 3: 若改动触及 ApiHandler 测试，再跑相关测试**

Run:

```powershell
& ".\gradlew.bat" :app:testDebugUnitTest --tests "*ApiHandler*"
```

Expected:

```text
BUILD SUCCESSFUL
```

---

### 验收标准

- `问题1, 回答1, 问题2, 回答2` 中重答 `回答1` 后，列表变为 `问题1, 新回答1, 问题2, 回答2`。
- 重答 `回答2` 后，列表变为 `问题1, 回答1, 问题2, 新回答2`。
- 新回答请求的上下文只包含被重答问题之前的历史，不把后续问题2传入本次重答请求。
- 旧回答关联媒体被清理，后续问题2及回答2的媒体不被清理。
- `RegenerateControllerTest` 和 `:app:compileDebugKotlin` 通过。
