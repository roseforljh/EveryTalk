package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentRunManualCancellationPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var store: AgentRunStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AgentRunStore(database.agentDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `按消息停止活动Run且不覆盖已经完成的Run`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-1", 1L, 1L, false))
        database.agentDao().upsertRun(run("run-active", "message-active", AgentRunStatus.WAITING_MODEL))
        database.agentDao().upsertRun(run("run-complete", "message-complete", AgentRunStatus.COMPLETED))
        database.agentDao().upsertContinuationState(continuationState())

        store.cancelActiveRunByVisibleMessage("message-active", AgentTerminalReasons.USER_STOP)
        store.cancelActiveRunByVisibleMessage("message-complete", AgentTerminalReasons.USER_STOP)

        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun("run-active")?.status)
        assertEquals(AgentTerminalReasons.USER_STOP, database.agentDao().getRun("run-active")?.terminalReason)
        assertEquals(AgentRunStatus.COMPLETED.name, database.agentDao().getRun("run-complete")?.status)
        assertNull(
            database.agentDao().getContinuationState(
                "session-1",
                "config-1",
                "CODEX",
                "OpenAI",
                "https://api.openai.com",
                "gpt-5.6",
            ),
        )
    }

    private fun run(id: String, messageId: String, status: AgentRunStatus) = AgentRunEntity(
        id = id,
        sessionId = "session-1",
        userMessageId = "user-$id",
        visibleAssistantMessageId = messageId,
        configIdSnapshot = null,
        requestSnapshotJson = null,
        status = status.name,
        currentRequestOrdinal = 0,
        terminalReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun continuationState() = ProviderContinuationStateEntity(
        id = "continuation-1",
        sessionId = "session-1",
        configId = "config-1",
        protocol = "CODEX",
        provider = "OpenAI",
        endpoint = "https://api.openai.com",
        model = "gpt-5.6",
        systemPromptFingerprint = "system",
        toolSchemaFingerprint = "tools",
        summarizedThroughItemId = null,
        opaqueStateJson = "{}",
        updatedAt = 1L,
    )
}
