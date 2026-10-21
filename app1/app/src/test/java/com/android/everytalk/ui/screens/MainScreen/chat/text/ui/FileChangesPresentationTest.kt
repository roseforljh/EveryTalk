package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.everytalk.data.DataClass.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 直接验证生产组件的任务切换、完成转场和保存差异入口，不复制组件的判断逻辑。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class FileChangesPresentationTest {
    @get:Rule val rule = createComposeRule()

    private fun reply(id: String, count: Int = 1): Message = Message(
        id = id, text = "修改完成", sender = Sender.AI,
        executionSteps = (1..count).map { index ->
            ExecutionStep("$id-$index", ExecutionStepType.Agent, "编辑文件", completed = true,
                fileChange = ExecutionFileChange("src/$id-$index.kt", false,
                    listOf(ExecutionFileEdit("before-$index", "after-$index"))))
        },
    )

    @Test fun `运行提示结束消失并在对应回复留下总结且不串入下次任务`() {
        val message = reply("current")
        val messages = listOf(reply("history", 4), message)
        val running = mutableStateOf(true)
        val activeId = mutableStateOf<String?>(message.id)
        rule.setContent { MaterialTheme { Column {
            FileChangesCapsule(messages, activeId.value, running.value)
            MessageFileChangesSummary(message, running.value && activeId.value == message.id)
        } } }
        rule.onNodeWithText("1 个文件已更改").assertIsDisplayed()
        rule.onNodeWithText("4 个文件已更改").assertDoesNotExist()
        rule.onNodeWithText("查看变更").assertDoesNotExist()
        // 即使旧 ID 晚一帧清除，任务结束也必须移除输入框提示和已打开的临时弹层。
        rule.onNodeWithText("1 个文件已更改").performClick()
        rule.onNodeWithText("文件改动").assertIsDisplayed()
        rule.runOnIdle { running.value = false }
        rule.onNodeWithText("1 个文件已更改").assertDoesNotExist()
        rule.onNodeWithText("文件改动").assertDoesNotExist()
        rule.onNodeWithText("已更改 current-1.kt").assertIsDisplayed()
        rule.onNodeWithText("查看变更").assertIsDisplayed()
        rule.runOnIdle { running.value = true; activeId.value = "next-without-changes" }
        rule.onNodeWithText("1 个文件已更改").assertDoesNotExist()
        rule.onNodeWithText("已更改 current-1.kt").assertIsDisplayed()
        rule.runOnIdle { activeId.value = null }
        rule.onNodeWithText("1 个文件已更改").assertDoesNotExist()
    }

    @Test fun `多文件总结默认三项展开收起并可打开指定文件差异`() {
        val message = reply("saved", 4)
        rule.setContent { MaterialTheme { MessageFileChangesSummary(message, false) } }
        rule.onNodeWithText("4 个文件已更改").assertIsDisplayed()
        rule.onNodeWithText("saved-3.kt").assertIsDisplayed()
        rule.onNodeWithText("saved-4.kt").assertDoesNotExist()
        rule.onNodeWithText("再显示 1 个文件").performClick()
        rule.onNodeWithText("saved-4.kt").assertIsDisplayed()
        rule.onNodeWithText("收起文件").performClick()
        rule.onNodeWithText("saved-4.kt").assertDoesNotExist()
        rule.onNodeWithText("saved-2.kt").performClick()
        rule.onNodeWithText("文件改动").assertIsDisplayed()
        rule.onNodeWithText("before-2").assertIsDisplayed()
        rule.onNodeWithText("after-2").assertIsDisplayed()
        rule.onNodeWithText("before-1").assertDoesNotExist()
    }

    @Test fun `无改动及失败未完成步骤不显示总结旧trace仍能恢复`() {
        val original = reply("old")
        val message = mutableStateOf(original.copy(executionSteps = emptyList()))
        rule.setContent { MaterialTheme { MessageFileChangesSummary(message.value, false) } }
        rule.onNodeWithText("查看变更").assertDoesNotExist()
        rule.runOnIdle { message.value = original.copy(executionSteps = original.executionSteps.map { it.copy(completed = false) }) }
        rule.onNodeWithText("查看变更").assertDoesNotExist()
        rule.runOnIdle { message.value = original.copy(executionSteps = original.executionSteps.map { it.copy(isError = true) }) }
        rule.onNodeWithText("查看变更").assertDoesNotExist()
        rule.runOnIdle { message.value = original.copy(executionSteps = emptyList(),
            executionTrace = original.executionSteps.map { ExecutionTraceEvent.Tool(it) }) }
        rule.onNodeWithText("已更改 old-1.kt").assertIsDisplayed()
        rule.onNodeWithText("查看变更").performClick()
        rule.onNodeWithText("after-1").assertIsDisplayed()
    }
}
