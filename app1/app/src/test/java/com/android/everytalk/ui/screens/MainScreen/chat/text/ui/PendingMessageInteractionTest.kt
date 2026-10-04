package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.statecontroller.ChatRunState
import com.android.everytalk.statecontroller.ComposerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 最小检查：锁定主按钮矩阵和 Pending 的原位编辑、条件抢占、恢复语义。 */
class PendingMessageInteractionTest {
    @Test
    fun `未修改历史消息显示复原有改动显示发送写库中不可点击`() {
        assertEquals(ComposerPrimaryAction.RESTORE, resolveComposerPrimaryAction(
            ChatRunState.Idle, ComposerMode.Normal, true, false, canRestoreMessage = true,
        ))
        assertEquals(ComposerPrimaryAction.SEND, resolveComposerPrimaryAction(
            ChatRunState.Idle, ComposerMode.Normal, true, false, canRestoreMessage = false,
        ))
        assertEquals(ComposerPrimaryAction.LOADING, resolveComposerPrimaryAction(
            ChatRunState.Idle, ComposerMode.Normal, true, false, isConvertingLongText = true, canRestoreMessage = true,
        ))
    }
    @Test
    fun `主按钮遵循空闲流式暂停和编辑矩阵`() {
        assertEquals(
            ComposerPrimaryAction.SEND,
            resolveComposerPrimaryAction(ChatRunState.Idle, ComposerMode.Normal, true, false),
        )
        assertEquals(
            ComposerPrimaryAction.PAUSE,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, false, false),
        )
        assertEquals(
            ComposerPrimaryAction.FORCE_STOP,
            resolveComposerPrimaryAction(ChatRunState.PauseRequested, ComposerMode.Normal, false, false),
        )
        assertEquals(
            ComposerPrimaryAction.SEND,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, true, false),
        )
        assertEquals(
            ComposerPrimaryAction.RESUME,
            resolveComposerPrimaryAction(ChatRunState.Paused, ComposerMode.Normal, false, false),
        )
        assertEquals(
            ComposerPrimaryAction.SEND,
            resolveComposerPrimaryAction(
                ChatRunState.Streaming,
                ComposerMode.EditingPending("id", "conversation", 1, "old", "old", emptyList(), emptyList()),
                true,
                false,
            ),
        )
    }

    @Test
    fun `转换中停止中和Run注册前不能显示可操作按钮`() {
        val states = listOf(ChatRunState.Idle, ChatRunState.Streaming, ChatRunState.PauseRequested, ChatRunState.Paused)
        states.forEach { state ->
            listOf(false, true).forEach { hasDraft ->
                assertEquals(ComposerPrimaryAction.LOADING,
                    resolveComposerPrimaryAction(state, ComposerMode.Normal, hasDraft, true))
                assertEquals(ComposerPrimaryAction.LOADING,
                    resolveComposerPrimaryAction(state, ComposerMode.Normal, hasDraft, false, isConvertingLongText = true))
            }
        }
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, false, false, isRunControllable = false))
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.Paused, ComposerMode.Normal, false, false, isRunControllable = false))
    }

    @Test
    fun `审批等待和请求准备没有暂停控制器时仍能停止已登记消息`() {
        listOf(ChatRunState.Streaming, ChatRunState.PauseRequested, ChatRunState.Paused).forEach { state ->
            assertEquals(ComposerPrimaryAction.STOP,
                resolveComposerPrimaryAction(state, ComposerMode.Normal, false, false,
                    isRunControllable = false, hasActiveMessage = true))
            assertEquals(ComposerPrimaryAction.LOADING,
                resolveComposerPrimaryAction(state, ComposerMode.Normal, false, true,
                    isRunControllable = false, hasActiveMessage = true))
        }
        // 尚未登记消息的 Pending 派发窗口不能误停，也不能把正常暂停改成停止。
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, false, false,
                isRunControllable = false, hasActiveMessage = false))
        assertEquals(ComposerPrimaryAction.PAUSE,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, false, false,
                isRunControllable = true, hasActiveMessage = true))
        assertEquals(ComposerPrimaryAction.VOICE,
            resolveComposerPrimaryAction(ChatRunState.Idle, ComposerMode.Normal, false, false,
                isRunControllable = false, hasActiveMessage = true))
    }

    @Test
    fun `远端取消超时后可重试但带草稿也不能绕过取消屏障发送`() {
        listOf(ChatRunState.Idle, ChatRunState.Streaming).forEach { state ->
            listOf(false, true).forEach { hasDraft ->
                assertEquals(ComposerPrimaryAction.RETRY_STOP,
                    resolveComposerPrimaryAction(state, ComposerMode.Normal, hasDraft, true,
                        canRetryRemoteStop = true))
                assertEquals(ComposerPrimaryAction.LOADING,
                    resolveComposerPrimaryAction(state, ComposerMode.Normal, hasDraft, true,
                        canRetryRemoteStop = false))
            }
        }
    }

    @Test
    fun `暂停请求和已暂停仍允许提交草稿但无草稿只在安全暂停后显示三角形`() {
        listOf(ChatRunState.PauseRequested, ChatRunState.Paused).forEach { state ->
            assertEquals(ComposerPrimaryAction.SEND,
                resolveComposerPrimaryAction(state, ComposerMode.Normal, true, false))
            assertEquals(ComposerPrimaryAction.SEND,
                resolveComposerPrimaryAction(state,
                    ComposerMode.EditingPending("id", "conversation", 1, "old", "old", emptyList(), emptyList()), false, false))
        }
        assertEquals(ComposerPrimaryAction.VOICE,
            resolveComposerPrimaryAction(ChatRunState.Idle, ComposerMode.Normal, false, false))
    }

    @Test
    fun `按钮图标和点击共用状态且暂停继续与强停分离`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val input = File(root, "app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatInputArea.kt").readText()
        val click = input.substringAfter("val onSendClick: () -> Unit").substringBefore("val inputBackgroundColor")
        assertTrue(input.contains("val hasContent = localText.isNotBlank()"))
        assertTrue(input.contains("val buttonState = primaryAction"))
        assertTrue(click.contains("onPauseStreaming()"))
        assertTrue(click.contains("onResumeStreaming()"))
        assertTrue(click.contains("viewModel.forceStopPendingPause(controlledMessageId)"))
        assertTrue(click.contains("viewModel.stopUncontrolledRun(controlledMessageId)"))
        assertTrue(input.contains("hasActiveMessage = controlledMessageId != null"))
        assertTrue(input.contains("ComposerPrimaryAction.STOP -> painterResource(R.drawable.ic_stop)"))
        assertTrue(!input.contains("IconButton(onClick = viewModel::onCancelAPICall)"))
        assertTrue(input.contains("state == ComposerPrimaryAction.LOADING || state == ComposerPrimaryAction.FORCE_STOP"))
        assertTrue(input.contains("enabled = state == primaryAction && state != ComposerPrimaryAction.LOADING"))
    }

    @Test
    fun `单按钮从安全暂停等待到强停处理中不再接受第三次点击`() {
        assertEquals(ComposerPrimaryAction.PAUSE,
            resolveComposerPrimaryAction(ChatRunState.Streaming, ComposerMode.Normal, false, false))
        assertEquals(ComposerPrimaryAction.FORCE_STOP,
            resolveComposerPrimaryAction(ChatRunState.PauseRequested, ComposerMode.Normal, false, false))
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.PauseRequested, ComposerMode.Normal, false, true))
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.Idle, ComposerMode.Normal, false, true))
        assertEquals(ComposerPrimaryAction.VOICE,
            resolveComposerPrimaryAction(ChatRunState.Idle, ComposerMode.Normal, false, false))
        assertEquals(ComposerPrimaryAction.RESUME,
            resolveComposerPrimaryAction(ChatRunState.Paused, ComposerMode.Normal, false, false))
        assertEquals(ComposerPrimaryAction.LOADING,
            resolveComposerPrimaryAction(ChatRunState.PauseRequested, ComposerMode.Normal, false, false, isRunControllable = false))
    }

    @Test
    fun `Pending编辑与派发使用原位更新和条件抢占`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val dao = File(root, "app/src/main/java/com/android/everytalk/data/database/daos/ChatDao.kt")
            .readText(Charsets.UTF_8)
        val controller = File(root, "app/src/main/java/com/android/everytalk/statecontroller/message/PendingMessageController.kt")
            .readText(Charsets.UTF_8)
        val viewModel = File(root, "app/src/main/java/com/android/everytalk/statecontroller/viewmodel/AppViewModel.kt")
            .readText(Charsets.UTF_8)

        assertTrue(dao.contains("SET content = :content"))
        assertTrue(dao.contains("WHERE id = :id AND status = 'EDITING'"))
        assertTrue(dao.contains("SET status = 'EDITING' WHERE id = :id AND status = 'PENDING'"))
        assertTrue(dao.contains("SET status = 'DISPATCHING' WHERE id = :id AND status = 'PENDING'"))
        assertTrue(dao.contains("deletePersistedPendingDispatches"))
        assertTrue(dao.contains("restoreInterruptedPendingDispatches"))
        assertTrue(controller.contains("nextDispatchablePending("))
        assertTrue(controller.contains("editingPosition"))
        assertTrue(controller.contains("steerCurrentRun(pending)"))
        assertTrue(controller.contains("steerCurrentRun(pending)"))
        assertTrue(viewModel.contains("manualMessageId = pending.id"))

        val inputComponents = File(
            root,
            "app/src/main/java/com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatInputComponents.kt",
        ).readText(Charsets.UTF_8)
        assertTrue(!inputComponents.contains("pending_message_editing"))
    }
}
