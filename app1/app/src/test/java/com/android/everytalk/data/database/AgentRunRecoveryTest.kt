package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentRunRecoveryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `App重启会封存活动Run和请求`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-1", 1L, 1L, isImageGeneration = false),
        )
        dao.upsertRun(
            AgentRunEntity(
                id = "run-1",
                sessionId = "session-1",
                userMessageId = "user-1",
                visibleAssistantMessageId = "assistant-1",
                configIdSnapshot = "config-1",
                status = "WAITING_MODEL",
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        dao.upsertRequest(
            AgentRequestEntity(
                id = "request-1",
                runId = "run-1",
                ordinal = 1,
                purpose = "AGENT_TURN",
                modelTurnOrdinal = 1,
                attempt = 1,
                retryOfRequestId = null,
                provider = "OpenAI",
                endpoint = "https://example.test",
                model = "model",
                payloadFingerprint = "fingerprint",
                status = "STREAMING",
                finishReason = null,
                startedAt = 1L,
                firstEventAt = null,
                finishedAt = null,
            ),
        )

        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        assertEquals("INTERRUPTED", dao.getRun("run-1")?.status)
        assertEquals("APP_PROCESS_RESTARTED", dao.getRun("run-1")?.terminalReason)
        assertEquals("INTERRUPTED", dao.getRequests("run-1").single().status)
        assertEquals(10L, dao.getRequests("run-1").single().finishedAt)
    }
}
