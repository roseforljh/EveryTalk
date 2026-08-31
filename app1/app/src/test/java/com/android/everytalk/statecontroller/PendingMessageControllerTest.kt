package com.android.everytalk.statecontroller

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.agent.AgentRunControlState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PendingMessageControllerTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        stopKoin()
    }

    @Test
    fun `编辑项从UI消失并挡住后续FIFO`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("one", "一", 1))
        dao.enqueuePendingMessage(pending("two", "二", 2))
        dao.enqueuePendingMessage(pending("three", "三", 3))
        val isRunning = MutableStateFlow(true)
        val dispatched = mutableListOf<String>()
        val finishes = mutableMapOf<String, () -> Unit>()
        val firstDispatch = CompletableDeferred<Unit>()
        var composerText = ""
        val controller = controller(
            isRunning = isRunning,
            replaceComposer = { text -> composerText = text },
            dispatch = { message, accepted, _, finished ->
                dispatched += message.id
                accepted()
                finishes[message.id] = finished
                firstDispatch.complete(Unit)
            },
        )
        controller.pendingMessages.first { it.size == 3 }

        controller.beginEdit("two")
        dao.observePendingMessages("conversation").first { rows -> rows.any { it.id == "two" && it.status == "EDITING" } }
        assertEquals("二", composerText)
        assertEquals(listOf("one", "three"), controller.visiblePendingMessages.first { it.size == 2 }.map { it.id })

        isRunning.value = false
        firstDispatch.await()
        assertEquals(listOf("one"), dispatched)
        finishes.getValue("one").invoke()
        advanceUntilIdle()
        assertEquals("③不能越过正在编辑的②", listOf("one"), dispatched)

        isRunning.value = true
        controller.commitEdit("二改", "二改", emptyList(), emptyList()) {}
        val restored = dao.observePendingMessages("conversation").first { rows ->
            rows.firstOrNull()?.id == "two" && rows.first().status == "PENDING"
        }
        assertEquals(listOf("two", "three"), restored.map { it.id })
        assertEquals(2L, restored.first().createdAt)
        assertEquals("二改", restored.first().content)
    }

    @Test
    fun `调整方案在Streaming中立即发送且双击不重复`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("adjust", "调整方案", 1))
        val isRunning = MutableStateFlow(true)
        var steerCount = 0
        val steered = mutableListOf<String>()
        val steerSignal = CompletableDeferred<Unit>()
        val controller = controller(
            isRunning = isRunning,
            steer = { steerCount++; steered += "adjust"; steerSignal.complete(Unit); true },
            dispatch = { _, accepted, _, _ -> accepted() },
        )
        controller.pendingMessages.first { it.size == 1 }

        controller.sendNow("adjust")
        controller.sendNow("adjust")
        steerSignal.await()
        dao.observePendingMessages("conversation").first { it.isEmpty() }
        controller.pendingMessages.first { it.isEmpty() }

        assertEquals(1, steerCount)
        assertEquals(listOf("adjust"), steered)
        assertTrue(controller.visiblePendingMessages.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `Safe Pause期间Pending不会自动派发`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("one", "一", 1))
        val isRunning = MutableStateFlow(true)
        val controlState = MutableStateFlow(AgentRunControlState.PAUSED)
        val dispatched = mutableListOf<String>()
        val controller = controller(
            isRunning = isRunning,
            agentRunControlState = controlState,
            dispatch = { message, _, _, _ -> dispatched += message.id },
        )
        controller.pendingMessages.first { it.size == 1 }

        advanceUntilIdle()
        assertTrue(dispatched.isEmpty())

        isRunning.value = false
        advanceUntilIdle()
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `Abort清理期间Pending不会抢跑`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("one", "一", 1))
        val isRunning = MutableStateFlow(true)
        val aborting = MutableStateFlow(true)
        val dispatched = mutableListOf<String>()
        val dispatchSignal = CompletableDeferred<Unit>()
        val controller = controller(
            isRunning = isRunning,
            isAbortInProgress = aborting,
            dispatch = { message, _, _, _ ->
                dispatched += message.id
                dispatchSignal.complete(Unit)
            },
        )
        controller.pendingMessages.first { it.size == 1 }
        advanceUntilIdle()
        assertTrue(dispatched.isEmpty())

        aborting.value = false
        isRunning.value = false
        dispatchSignal.await()
        assertEquals(listOf("one"), dispatched)
    }

    @Test
    fun `PauseRequested期间即使Run失败Pending也不会被清理中间态触发`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("one", "一", 1))
        val isRunning = MutableStateFlow(true)
        val pauseRequested = MutableStateFlow(AgentRunControlState.PAUSE_REQUESTED)
        val dispatched = mutableListOf<String>()
        val controller = controller(
            isRunning = isRunning,
            agentRunControlState = pauseRequested,
            dispatch = { message, _, _, _ -> dispatched += message.id },
        )
        controller.pendingMessages.first { it.size == 1 }

        isRunning.value = false
        advanceUntilIdle()
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `没有AgentRun时调整方案直接复用普通发送链路`() = runTest {
        val dao = database.chatDao()
        dao.enqueuePendingMessage(pending("adjust", "调整方案", 1))
        val isRunning = MutableStateFlow(true)
        val dispatchSignal = CompletableDeferred<Unit>()
        var dispatchCount = 0
        val controller = controller(
            isRunning = isRunning,
            steer = { false },
            dispatch = { message, accepted, _, finished ->
                dispatchCount++
                assertEquals("adjust", message.id)
                accepted()
                finished()
                dispatchSignal.complete(Unit)
            },
        )
        controller.pendingMessages.first { it.size == 1 }

        controller.sendNow("adjust")
        dispatchSignal.await()
        advanceUntilIdle()

        assertEquals(1, dispatchCount)
        assertTrue(dao.observePendingMessages("conversation").first().isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        isRunning: MutableStateFlow<Boolean>,
        replaceComposer: (String) -> Unit = {},
        steer: () -> Boolean = { true },
        isAbortInProgress: MutableStateFlow<Boolean> = MutableStateFlow(false),
        agentRunControlState: MutableStateFlow<AgentRunControlState> =
            MutableStateFlow(AgentRunControlState.RUNNING),
        dispatch: (PendingMessageEntity, () -> Unit, () -> Unit, () -> Unit) -> Unit,
    ) = PendingMessageController(
        chatDao = database.chatDao(),
        scope = backgroundScope,
        currentConversationId = MutableStateFlow("conversation"),
        isApiCalling = isRunning,
        isAbortInProgress = isAbortInProgress,
        agentRunControlState = agentRunControlState,
        hasComposerContent = { false },
        replaceComposer = { text, _ -> replaceComposer(text) },
        persistAttachments = { attachments, _ -> attachments },
        resumeStreaming = {},
        steerCurrentRun = { steer() },
        showMessage = {},
        dispatch = dispatch,
    )

    private fun pending(id: String, content: String, createdAt: Long) = PendingMessageEntity(
        id = id,
        conversationId = "conversation",
        content = content,
        composerText = content,
        createdAt = createdAt,
        updatedAt = createdAt,
        status = "PENDING",
        queuePosition = -1,
    )
}
