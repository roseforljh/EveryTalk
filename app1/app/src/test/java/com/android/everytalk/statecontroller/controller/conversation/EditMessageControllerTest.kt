package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ChatRunState
import com.android.everytalk.statecontroller.ComposerMode
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.restoreSkillEditor
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.buildSkillContentParts
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.displaySkillEditorText
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ComposerPrimaryAction
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveComposerPrimaryAction
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditMessageControllerTest {
    @Test
    fun `发送时正文trim但内容分段保留空白回填后仍可复原`() {
        // MessageSender 保存 text.trim()，但 contentParts 保留输入原文；回填也从分段恢复。
        val original = user("u1", "cs").copy(contentParts = listOf(MessageContentPart.Text("cs\n")))
        val session = MessageEditSession("u1", false, original, listOf(original), listOf(original), emptyList())
        val editor = restoreSkillEditor(original.text, original.contentParts)
        assertTrue(session.matchesDraft(
            displaySkillEditorText(editor.value.text, editor.references), emptyList(),
            buildSkillContentParts(editor.value.text, editor.references),
        ))
        assertFalse(session.matchesDraft("cs", emptyList(), listOf(MessageContentPart.Text("cs"))))
    }

    @Test
    fun `实际输入框状态列表回填cs后主按钮应为复原`() = runTest {
        val original = user("u1", "cs")
        val originalMessages = listOf(original, ai("a1"))
        val state = ViewModelStateHolder().apply {
            setCurrentConversationId("u1")
            messages.addAll(originalMessages)
        }
        val controller = EditMessageController(state, mockk(relaxed = true), this, Mutex(), { _, _ -> }, {})
        controller.requestEditMessage(original)
        advanceUntilIdle()
        val session = checkNotNull(controller.editSession.value)
        val editor = restoreSkillEditor(original.text, original.contentParts)
        val canRestore = session.belongsTo(state._currentConversationId.value, false) && session.matchesDraft(
            editor.value.text, state.selectedMediaItems,
            buildSkillContentParts(editor.value.text, editor.references),
        )
        assertEquals(
            ComposerPrimaryAction.RESTORE,
            resolveComposerPrimaryAction(
                ChatRunState.Idle,
                ComposerMode.Normal,
                true, false, canRestoreMessage = canRestore,
            ),
        )
        controller.restoreOriginalMessages(editor.value.text, state.selectedMediaItems, null, false)
        advanceUntilIdle()
        assertRestored(originalMessages, state.messages.toList())
    }

    @Test
    fun `当前会话尚未迁移历史ID也能复原`() = runTest {
        for (imageMode in listOf(false, true)) {
            val original = user("u1", "cs")
            val originalMessages = listOf(original, ai("a1"))
            val state = ViewModelStateHolder().apply {
                setCurrentConversationId("new_chat_123")
                _currentImageGenerationConversationId.value = "new_image_generation_123"
            }
            val messages = if (imageMode) state.imageGenerationMessages else state.messages
            messages.addAll(originalMessages)
            val history = mockk<HistoryManager>(relaxed = true)
            val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
            controller.requestEditMessage(original, imageMode)
            advanceUntilIdle()
            val currentId = if (imageMode) state._currentImageGenerationConversationId.value else state._currentConversationId.value
            val session = checkNotNull(controller.editSession.value)
            assertTrue(session.belongsTo(currentId, imageMode))
            assertTrue(session.belongsTo("u1", imageMode))
            assertFalse(session.belongsTo("another", imageMode))
            assertFalse(session.belongsTo(currentId, !imageMode))
            controller.restoreOriginalMessages(original.text, emptyList(), null, imageMode)
            advanceUntilIdle()
            assertRestored(originalMessages, messages.toList())
            coVerify { history.rewindConversation("u1", match { it.isNotEmpty() }, imageMode, currentId) }
        }
    }

    @Test
    fun `状态列表中的附件按内容及顺序比较`() {
        val first = SelectedMediaItem.ImageFromBitmap("one", "1")
        val second = SelectedMediaItem.ImageFromBitmap("two", "2")
        val original = user("u1").copy(attachments = listOf(first, second))
        val session = MessageEditSession("u1", false, original, listOf(original), listOf(original), emptyList())
        val attachments = androidx.compose.runtime.mutableStateListOf(first, second)
        assertTrue(session.matchesDraft(original.text, attachments))
        attachments.reverse()
        assertFalse(session.matchesDraft(original.text, attachments))
        attachments.reverse()
        assertTrue(session.matchesDraft(original.text, attachments))
        attachments.removeAt(0)
        assertFalse(session.matchesDraft(original.text, attachments))
    }

    @Test
    fun `只含附件也可以复原Skill相同文字但引用不同算改动`() {
        val photo = SelectedMediaItem.ImageFromBitmap("photo", "photo")
        val original = user("u1", "").copy(attachments = listOf(photo))
        val session = MessageEditSession("u1", false, original, listOf(original), listOf(original), emptyList())
        assertTrue(session.matchesDraft("", listOf(photo), emptyList()))
        val reference = com.android.everytalk.data.skill.MessageSkillReference(
            skillId = "skill", displayName = "技能", sourceType = com.android.everytalk.data.skill.SkillSourceType.USER_CREATED, contentHash = "v1",
        )
        val parts = listOf(com.android.everytalk.data.DataClass.MessageContentPart.SkillReference(reference))
        val withSkill = session.copy(draft = original.copy(text = "‹技能›", contentParts = parts))
        assertTrue(withSkill.matchesDraft("‹技能›", listOf(photo), parts))
        assertFalse(withSkill.matchesDraft("‹技能›", listOf(photo), listOf(
            com.android.everytalk.data.DataClass.MessageContentPart.SkillReference(reference.copy(contentHash = "v2")),
        )))
    }

    @Test
    fun `新消息发送成功后不能再用旧快照复原`() = runTest {
        val original = user("u1")
        val state = ViewModelStateHolder().apply { setCurrentConversationId("u1"); messages.add(original) }
        val history = mockk<HistoryManager>(relaxed = true)
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
        controller.requestEditMessage(original)
        advanceUntilIdle()
        controller.finishEditing(checkNotNull(controller.editSession.value))
        controller.restoreOriginalMessages(original.text, emptyList(), null, false)
        advanceUntilIdle()
        assertNull(controller.editSession.value)
        assertTrue(state.messages.isEmpty())
        coVerify(exactly = 1) { history.rewindConversation(any(), any(), any(), any()) }
    }
    @Test
    fun `未改动时复原完整对话新ID附件与后续回答并清空输入`() = runTest {
        for (imageMode in listOf(false, true)) {
            val original = user("u1", "原文\n").copy(attachments = listOf(SelectedMediaItem.ImageFromBitmap("data", "photo")))
            val originalMessages = listOf(original, ai("a1"), user("u2"), ai("a2"))
            val state = ViewModelStateHolder().apply {
                setCurrentConversationId("u1")
                _currentImageGenerationConversationId.value = "u1"
            }
            val messages = if (imageMode) state.imageGenerationMessages else state.messages
            messages.addAll(originalMessages)
            val history = mockk<HistoryManager>(relaxed = true)
            val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
            controller.requestEditMessage(original, imageMode)
            advanceUntilIdle()
            controller.consumeRestoredDraft(checkNotNull(controller.restoredDraft.value))
            assertTrue(messages.isEmpty())
            assertNotNull(controller.editSession.value)

            controller.restoreOriginalMessages(original.text, original.attachments, null, imageMode)
            advanceUntilIdle()

            assertRestored(originalMessages, messages.toList())
            assertEquals("", state._text.value)
            assertTrue(state.selectedMediaItems.isEmpty())
            assertNull(controller.editSession.value)
            coVerify(exactly = 1) { history.rewindConversation("u1", match { it.isNotEmpty() }, imageMode) }
        }
    }

    @Test
    fun `文字附件或顺序不同不能复原改回原样后可以复原`() = runTest {
        val attachments = listOf(SelectedMediaItem.ImageFromBitmap("one", "1"), SelectedMediaItem.ImageFromBitmap("two", "2"))
        val original = user("u1", "原文 ").copy(attachments = attachments)
        val state = ViewModelStateHolder().apply { setCurrentConversationId("u1"); messages.add(original) }
        val history = mockk<HistoryManager>(relaxed = true)
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
        controller.requestEditMessage(original)
        advanceUntilIdle()
        val session = checkNotNull(controller.editSession.value)
        assertFalse(session.matchesDraft("原文", attachments))
        assertFalse(session.matchesDraft(original.text, attachments.reversed()))
        assertFalse(session.matchesDraft(original.text, attachments.take(1)))
        assertFalse(session.matchesDraft(original.text, attachments + SelectedMediaItem.ImageFromBitmap("three", "3")))
        assertTrue(session.matchesDraft(original.text, attachments))
        controller.restoreOriginalMessages("改动", attachments, null, false)
        advanceUntilIdle()
        assertTrue(state.messages.isEmpty())
        controller.restoreOriginalMessages(original.text, attachments, null, false)
        advanceUntilIdle()
        assertRestored(listOf(original), state.messages.toList())
    }

    @Test
    fun `复原失败保留快照可重试且不覆盖其他会话`() = runTest {
        val original = user("u1")
        val state = ViewModelStateHolder().apply { setCurrentConversationId("u1"); messages.add(original) }
        val history = mockk<HistoryManager>(relaxed = true)
        val notices = mutableListOf<String>()
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, notices::add)
        controller.requestEditMessage(original)
        advanceUntilIdle()
        coEvery { history.rewindConversation("u1", match { it.isNotEmpty() }, false) } throws IllegalStateException("磁盘错误")
        controller.restoreOriginalMessages(original.text, emptyList(), null, false)
        advanceUntilIdle()
        assertNotNull(controller.editSession.value)
        assertEquals(original.text, state._text.value)
        assertTrue(state.messages.isEmpty())
        assertFalse(controller.restoring.value)
        assertEquals(1, notices.size)
        state.setCurrentConversationId("another")
        controller.restoreOriginalMessages(original.text, emptyList(), null, false)
        advanceUntilIdle()
        coVerify(exactly = 1) { history.rewindConversation("u1", match { it.isNotEmpty() }, false) }
        state.setCurrentConversationId("u1")
        coEvery { history.rewindConversation("u1", match { it.isNotEmpty() }, false) } returns Unit
        controller.restoreOriginalMessages(original.text, emptyList(), null, false)
        advanceUntilIdle()
        assertRestored(listOf(original), state.messages.toList())
        assertNull(controller.editSession.value)
    }
    private fun assertRestored(original: List<Message>, restored: List<Message>) {
        assertEquals(original.size, restored.size)
        original.zip(restored).forEach { (old, copy) ->
            assertNotEquals(old.id, copy.id)
            assertNotNull(copy.conversationId)
            assertEquals(old.copy(id = copy.id, conversationId = copy.conversationId), copy)
        }
    }

    private fun user(id: String, text: String = id) = Message(id = id, text = text, sender = Sender.User)
    private fun ai(id: String) = Message(id = id, text = id, sender = Sender.AI)

    @Test
    fun `回退中间消息时完整恢复附件并且不修改此前消息`() = runTest {
        val attachments = listOf(
            SelectedMediaItem.ImageFromUri(uri = mockk(), id = "photo", filePath = "photo.png"),
            SelectedMediaItem.ImageFromBitmap(bitmapData = "base64", id = "bitmap"),
            SelectedMediaItem.GenericFile(uri = mockk(), id = "pdf", displayName = "资料.pdf", mimeType = "application/pdf", filePath = "file.pdf"),
            SelectedMediaItem.Audio(id = "audio", mimeType = "audio/wav", filePath = "audio.wav"),
        )
        val original = user("u2", "  原始问题\n").copy(attachments = attachments)
        val before = listOf(user("u1"), ai("a1"))
        val state = ViewModelStateHolder().apply {
            setCurrentConversationId("u1")
            messages.addAll(before + original + ai("a2") + user("u3") + ai("a3"))
        }
        val history = mockk<HistoryManager>(relaxed = true)
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})

        controller.requestEditMessage(original.copy(text = "过期缓存"))
        advanceUntilIdle()

        assertEquals(before, state.messages.toList())
        assertEquals(original.text, state._text.value)
        assertEquals(attachments, state.selectedMediaItems.toList())
        assertEquals(original, controller.restoredDraft.value?.message)
        coVerify(exactly = 1) { history.rewindConversation("u1", before, false) }
        controller.consumeRestoredDraft(checkNotNull(controller.restoredDraft.value))
        assertNull(controller.restoredDraft.value)
    }

    @Test
    fun `首条纯附件生图消息回退为空且不影响文本对话`() = runTest {
        val original = user("image", "").copy(attachments = listOf(SelectedMediaItem.ImageFromBitmap("base64", "image-file")))
        val state = ViewModelStateHolder().apply {
            _currentImageGenerationConversationId.value = "image"
            messages.add(user("text"))
            imageGenerationMessages.addAll(listOf(original, ai("result")))
        }
        val history = mockk<HistoryManager>(relaxed = true)
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
        controller.requestEditMessage(original, true)
        advanceUntilIdle()
        assertTrue(state.imageGenerationMessages.isEmpty())
        assertEquals(listOf("text"), state.messages.map { it.id })
        assertEquals(original.attachments, state.selectedMediaItems.toList())
        assertTrue(checkNotNull(controller.restoredDraft.value).isImageGeneration)
        coVerify { history.rewindConversation("image", emptyList(), true) }
    }

    @Test
    fun `保存失败保留全部消息和附件且不回填半成品`() = runTest {
        val original = user("u1")
        val state = ViewModelStateHolder().apply { messages.addAll(listOf(original, ai("a1"))) }
        val history = mockk<HistoryManager>()
        coEvery { history.rewindConversation(any(), any(), any(), any()) } throws IllegalStateException("磁盘写入失败")
        val notices = mutableListOf<String>()
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, notices::add)
        controller.requestEditMessage(original)
        advanceUntilIdle()
        assertEquals(listOf("u1", "a1"), state.messages.map { it.id })
        assertEquals(original, state.messages.first())
        assertEquals("", state._text.value)
        assertNull(controller.restoredDraft.value)
        assertEquals(1, notices.size)
    }

    @Test
    fun `已有草稿运行中或目标不在当前模式时不回退`() = runTest {
        val original = user("u1")
        val state = ViewModelStateHolder().apply { messages.add(original) }
        val history = mockk<HistoryManager>(relaxed = true)
        val controller = EditMessageController(state, history, this, Mutex(), { _, _ -> }, {})
        state._text.value = "尚未发送"
        controller.requestEditMessage(original)
        state._text.value = ""
        state._isTextApiCalling.value = true
        controller.requestEditMessage(original)
        state._isTextApiCalling.value = false
        controller.requestEditMessage(original, true)
        advanceUntilIdle()
        assertEquals(listOf(original), state.messages.toList())
        assertNull(controller.restoredDraft.value)
        coVerify(exactly = 0) { history.rewindConversation(any(), any(), any(), any()) }
    }

    @Test
    fun `编辑Agent追加消息同时裁掉之前AI气泡里越过边界的内容`() {
        val reply = ai("a1").copy(executionTrace = listOf(
            ExecutionTraceEvent.Content("保留的回答"),
            ExecutionTraceEvent.UserMessageBoundary("u2"),
            ExecutionTraceEvent.Content("应被撤回的回答"),
        ))
        val result = messagesBeforeEdit(listOf(user("u1"), reply, user("u2")), 2)
        assertEquals("保留的回答", result.last().text)
        assertEquals(listOf(ExecutionTraceEvent.Content("保留的回答")), result.last().executionTrace)
        assertTrue(result.last().parts.isEmpty())
    }
}
