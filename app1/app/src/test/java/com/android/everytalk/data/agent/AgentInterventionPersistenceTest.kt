package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentCapabilityGrantEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentResourceLeaseEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentInterventionPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var store: AgentInterventionStore
    private lateinit var broker: AgentInterventionBroker

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AgentInterventionStore(database.agentDao())
        broker = AgentInterventionBroker(store)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `并发 suspend 只创建一个 active suspension`() = runBlocking {
        val run = insertRun("run-dedup")
        val tickets = listOf("MODEL_HINT", "EXECUTOR_PROVEN").map { source ->
            async {
                broker.suspend(
                    run = run,
                    capabilityRequest = CapabilityRequest("git.push", "需要认证"),
                    turnId = "turn-1",
                    requestId = "request-1",
                    toolCallId = "tool-1",
                    executionSlot = "slot-1",
                    requestHash = "hash-1",
                    requestSource = source,
                    bindingGeneration = 1,
                    executionGeneration = 1,
                )
            }
        }.awaitAll()

        assertEquals(tickets[0].suspension.id, tickets[1].suspension.id)
        assertEquals(1, store.startupCandidates().count { it.id == tickets[0].suspension.id })
    }

    @Test
    fun `一次性 Grant 并发 claim 只有一个成功`() = runBlocking {
        val run = insertRun("run-grant")
        val grantStore = AgentCapabilityGrantStore(database.agentDao())
        grantStore.create(
            AgentCapabilityGrantEntity(
                grantId = "grant-1",
                capability = "git.push",
                runId = run.id,
                runGeneration = run.runGeneration,
                toolCallId = "tool-1",
                executionSlot = "slot-1",
                operation = "push",
                targetBinding = "repo-a",
                audience = "git-adapter",
                scope = "repo-a",
                issuedAt = 1,
                expiresAt = Long.MAX_VALUE,
                maxUses = 1,
                generation = 1,
            ),
        )

        val claims = (1..2).map { attempt ->
            async {
                grantStore.claimUse(
                    grantId = "grant-1",
                    runId = run.id,
                    runGeneration = run.runGeneration,
                    toolCallId = "tool-1",
                    executionSlot = "slot-1",
                    operation = "push",
                    targetBinding = "repo-a",
                    audience = "git-adapter",
                    generation = 1,
                    attemptId = "attempt-$attempt",
                )
            }
        }.awaitAll()

        assertEquals(1, claims.count { it })
    }

    @Test
    fun `重新输入轮换 nonce 后旧 nonce 失效`() = runBlocking {
        val run = insertRun("run-reentry")
        val ticket = broker.suspend(
            run = run,
            capabilityRequest = CapabilityRequest("terminal.interaction", "需要 OTP"),
            turnId = "turn-1",
            requestId = "request-1",
            toolCallId = "tool-1",
            executionSlot = "slot-1",
            requestHash = "hash-1",
            requestSource = "EXECUTOR_PROVEN",
            bindingGeneration = 1,
            executionGeneration = 1,
        )
        assertNotNull(ticket.resolutionNonce)
        val oldNonce = ticket.resolutionNonce!!
        assertTrue(store.resolve(ticket.suspension.id, SuspensionState.WAITING_USER, 0, oldNonce))
        assertTrue(store.enterUserReentry(ticket.suspension.id, SuspensionState.RESOLUTION_RECEIVED, 1, "new-nonce"))
        assertFalse(store.resolve(ticket.suspension.id, SuspensionState.WAITING_USER_REENTRY, 2, oldNonce))
        assertTrue(store.resolve(ticket.suspension.id, SuspensionState.WAITING_USER_REENTRY, 2, "new-nonce"))
    }

    @Test
    fun `Run 取消递增 generation 并废止旧 Grant`() = runBlocking {
        val run = insertRun("run-cancel")
        val grantStore = AgentCapabilityGrantStore(database.agentDao())
        grantStore.create(
            AgentCapabilityGrantEntity(
                grantId = "grant-cancel",
                capability = "git.push",
                runId = run.id,
                runGeneration = run.runGeneration,
                toolCallId = "tool-1",
                executionSlot = "slot-1",
                operation = "push",
                targetBinding = "repo-a",
                audience = "git-adapter",
                scope = "repo-a",
                issuedAt = 1,
                expiresAt = Long.MAX_VALUE,
                maxUses = 1,
                generation = 1,
            ),
        )
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        assertEquals(1L, database.agentDao().getRun(run.id)?.runGeneration)
        assertFalse(
            grantStore.claimUse(
                "grant-cancel",
                run.id,
                run.runGeneration,
                "tool-1",
                "slot-1",
                "push",
                "repo-a",
                "git-adapter",
                1,
                "attempt-after-cancel",
            ),
        )
    }

    @Test
    fun `Run 取消后旧 nonce 不能 resolve 且旧快照不能重新 suspend`() = runBlocking {
        val run = insertRun("run-cancel-suspension")
        val ticket = broker.suspend(
            run,
            CapabilityRequest("git.push", "需要认证"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        assertFalse(broker.resolve(ticket.suspension.id, ticket.suspension.rowVersion, ticket.resolutionNonce!!))
        assertTrue(
            runCatching {
                broker.suspend(
                    run,
                    CapabilityRequest("git.push", "再次请求认证"),
                    "turn-2",
                    "request-2",
                    "tool-2",
                    "slot-2",
                    "hash-2",
                    "EXECUTOR_PROVEN",
                    1,
                    1,
                )
            }.isFailure,
        )
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(run.id)?.status)
    }

    @Test
    fun `旧执行协程不能把已取消 Run 覆盖回执行态`() = runBlocking {
        val run = insertRun("run-cancel-dominates")
        val runStore = AgentRunStore(database.agentDao())
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        val persisted = runStore.updateRunStatus(run, AgentRunStatus.EXECUTING_TOOL)

        assertEquals(AgentRunStatus.CANCELLED.name, persisted.status)
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(run.id)?.status)
        assertEquals(1L, database.agentDao().getRun(run.id)?.runGeneration)
    }

    @Test
    fun `启动恢复显式处理 ephemeral resolution 和 delivered`() = runBlocking {
        val run = insertRun("run-recovery")
        val ephemeral = broker.suspend(
            run,
            CapabilityRequest("terminal.interaction", "需要 OTP"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        val durable = broker.suspend(
            run,
            CapabilityRequest("git.push", "需要认证"),
            "turn-1",
            "request-2",
            "tool-2",
            "slot-2",
            "hash-2",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        database.agentDao().upsertSuspension(ephemeral.suspension.copy(status = SuspensionState.RESOLUTION_RECEIVED.name))
        database.agentDao().upsertSuspension(durable.suspension.copy(status = SuspensionState.DELIVERED.name))

        val actions = AgentInterventionRecovery(database.agentDao(), store).recover()

        assertTrue(actions.any { it.suspensionId == ephemeral.suspension.id && it.action == "WAITING_USER_REENTRY" && it.newResolutionNonce != null })
        assertTrue(actions.any { it.suspensionId == durable.suspension.id && it.action == "READY_TO_RESUME" })
        assertEquals(SuspensionState.WAITING_USER_REENTRY.name, store.get(ephemeral.suspension.id)?.status)
        assertEquals(SuspensionState.READY_TO_RESUME.name, store.get(durable.suspension.id)?.status)
    }

    @Test
    fun `冷启动重新投影等待卡片时轮换 nonce`() = runBlocking {
        val run = insertRun("run-waiting-nonce")
        val ticket = broker.suspend(
            run,
            CapabilityRequest("git.push", "需要认证"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )

        val action = AgentInterventionRecovery(database.agentDao(), store).recover()
            .single { it.suspensionId == ticket.suspension.id }
        val recovered = requireNotNull(store.get(ticket.suspension.id))

        assertEquals("PROJECT_TO_UI", action.action)
        assertNotNull(action.newResolutionNonce)
        assertFalse(store.resolve(recovered.id, SuspensionState.WAITING_USER, recovered.rowVersion, ticket.resolutionNonce!!))
        assertTrue(store.resolve(recovered.id, SuspensionState.WAITING_USER, recovered.rowVersion, action.newResolutionNonce!!))
    }

    @Test
    fun `旧 Policy Suspension 不会被新版 Registry 静默解释`() = runBlocking {
        val run = insertRun("run-policy-stale")
        val ticket = broker.suspend(
            run,
            CapabilityRequest("git.push", "需要认证"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        database.agentDao().upsertSuspension(ticket.suspension.copy(policyVersion = "legacy"))

        val action = AgentInterventionRecovery(database.agentDao(), store).recover()
            .single { it.suspensionId == ticket.suspension.id }

        assertEquals("POLICY_STALE", action.action)
        assertEquals(SuspensionState.READY_TO_RESUME_WITH_FAILURE.name, store.get(ticket.suspension.id)?.status)
        assertEquals("POLICY_STALE", store.get(ticket.suspension.id)?.failureCode)
    }

    @Test
    fun `同一资源并发 Lease 只有一个 owner 成功`() = runBlocking {
        val run = insertRun("run-lease")
        val leaseStore = AgentResourceLeaseStore(database.agentDao())
        val claims = listOf("owner-a", "owner-b").map { owner ->
            async {
                leaseStore.claim(
                    AgentResourceLeaseEntity(
                        resourceRef = "pty-1",
                        leaseOwner = owner,
                        leaseKind = "PTY",
                        leaseGeneration = 1,
                        runId = run.id,
                        runGeneration = run.runGeneration,
                        issuedAt = 1,
                        expiresAt = Long.MAX_VALUE,
                    ),
                )
            }
        }.awaitAll()

        assertEquals(1, claims.count { it })
    }

    @Test
    fun `过期 Lease 只能由更高 generation 接管`() = runBlocking {
        val run = insertRun("run-lease-reclaim")
        val leaseStore = AgentResourceLeaseStore(database.agentDao())
        assertTrue(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "owner-a", "PTY", 1, run.id, run.runGeneration, 1, 10),
            ),
        )
        assertTrue(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "owner-b", "PTY", 2, run.id, run.runGeneration, 11, 100),
            ),
        )
        assertFalse(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "owner-c", "PTY", 2, run.id, run.runGeneration, 12, 100),
            ),
        )
    }

    @Test
    fun `MODEL_HINT 不能创建 sudo 密码介入`() {
        val error = runBlocking {
            val run = insertRun("run-sudo")
            runCatching {
                broker.suspend(
                    run,
                    CapabilityRequest("privilege.sudo.execute", "需要 sudo"),
                    "turn-1",
                    "request-1",
                    "tool-1",
                    "slot-1",
                    "hash-1",
                    "MODEL_HINT",
                    1,
                    1,
                )
            }.exceptionOrNull()
        }
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `OAuth callback 只能消费一次且校验绑定`() = runBlocking {
        val run = insertRun("run-oauth")
        val verifierStore = InMemoryVerifierStore()
        val oauth = AgentOAuthStore(database.agentDao(), verifierStore)
        val request = oauth.create(
            runId = run.id,
            runGeneration = run.runGeneration,
            capability = "git.push",
            targetBinding = "repo-a",
            clientId = "client-a",
            redirectUri = "everytalk://oauth/callback",
            verifierGeneration = 1,
            ttlMillis = 60_000,
        )

        assertNull(oauth.claimCallback(request.state, "git.push", "repo-b", "client-a", "everytalk://oauth/callback", 1))
        val claimed = oauth.claimCallback(request.state, "git.push", "repo-a", "client-a", "everytalk://oauth/callback", 1)
        assertNotNull(claimed)
        assertNull(oauth.claimCallback(request.state, "git.push", "repo-a", "client-a", "everytalk://oauth/callback", 1))
        oauth.cleanup(requireNotNull(claimed))
        assertTrue(verifierStore.values.isEmpty())
    }

    @Test
    fun `Run 取消后 OAuth callback 不得 claim`() = runBlocking {
        val run = insertRun("run-oauth-cancel")
        val oauth = AgentOAuthStore(database.agentDao(), InMemoryVerifierStore())
        val request = oauth.create(
            run.id,
            run.runGeneration,
            "git.push",
            "repo-a",
            "client-a",
            "everytalk://oauth/callback",
            1,
            60_000,
        )
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        assertNull(oauth.claimCallback(request.state, "git.push", "repo-a", "client-a", "everytalk://oauth/callback", 1))
    }

    @Test
    fun `Run 取消后不得创建 OAuth state 且 verifier 会清理`() = runBlocking {
        val run = insertRun("run-oauth-create-cancel")
        val verifierStore = InMemoryVerifierStore()
        val oauth = AgentOAuthStore(database.agentDao(), verifierStore)
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        assertTrue(
            runCatching {
                oauth.create(
                    run.id,
                    run.runGeneration,
                    "git.push",
                    "repo-a",
                    "client-a",
                    "everytalk://oauth/callback",
                    1,
                    60_000,
                )
            }.isFailure,
        )
        assertTrue(verifierStore.values.isEmpty())
    }

    @Test
    fun `StoredAuthorization 只返回安全引用并可撤销`() = runBlocking {
        val authorizations = AgentStoredAuthorizationStore(database.agentDao())
        authorizations.save(
            StoredAuthorization(
                authorizationId = "auth-1",
                provider = "github",
                credentialReference = "secure-ref-1",
                userConsentScope = "WORKSPACE",
                workspaceId = "workspace-1",
                issuedAt = 1,
                expiresAt = Long.MAX_VALUE,
                revoked = false,
                generation = 1,
            ),
        )

        assertNull(authorizations.getReference("auth-1", "github", 1, workspaceId = "workspace-2"))
        assertEquals("secure-ref-1", authorizations.getReference("auth-1", "github", 1, workspaceId = "workspace-1"))
        assertTrue(authorizations.revoke("auth-1"))
        assertNull(authorizations.getReference("auth-1", "github", 1, workspaceId = "workspace-1"))
        assertTrue(
            runCatching {
                authorizations.save(
                    StoredAuthorization(
                        authorizationId = "auth-1",
                        provider = "github",
                        credentialReference = "secure-ref-reopened",
                        userConsentScope = "WORKSPACE",
                        workspaceId = "workspace-1",
                        issuedAt = 2,
                        expiresAt = Long.MAX_VALUE,
                        revoked = false,
                        generation = 2,
                    ),
                )
            }.isFailure,
        )
    }

    private suspend fun insertRun(id: String): AgentRunEntity {
        val sessionId = "session-$id"
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1, 1, false))
        return AgentRunEntity(
            id = id,
            sessionId = sessionId,
            userMessageId = "user-$id",
            visibleAssistantMessageId = "assistant-$id",
            configIdSnapshot = null,
            requestSnapshotJson = null,
            status = AgentRunStatus.EXECUTING_TOOL.name,
            currentRequestOrdinal = 0,
            terminalReason = null,
            createdAt = 1,
            updatedAt = 1,
        ).also { database.agentDao().upsertRun(it) }
    }

    private class InMemoryVerifierStore : OAuthVerifierStore {
        val values = mutableMapOf<String, CharArray>()
        override suspend fun save(reference: String, verifier: CharArray) {
            values[reference] = verifier.copyOf()
        }
        override suspend fun load(reference: String): CharArray? = values[reference]?.copyOf()
        override suspend fun delete(reference: String) {
            values.remove(reference)?.fill('\u0000')
        }
    }
}
