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
            ComposerPrimaryAction.LOADING,
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
