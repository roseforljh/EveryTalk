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
import kotlinx.coroutines.CompletableDeferred
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
        assertEquals("EXECUTOR_PROVEN", store.get(tickets[0].suspension.id)?.requestSource)
    }

    @Test
    fun `Run 终止会原子封存等待中的 Suspension 和 Execution Slot`() = runBlocking {
        val run = insertRun("run-cancel-suspension")
        val ticket = broker.suspend(
            run = run,
            capabilityRequest = CapabilityRequest("server.restart.confirm", "确认重启"),
            turnId = "turn-1",
            requestId = "request-1",
            toolCallId = "tool-1",
            executionSlot = "slot-1",
            requestHash = "hash-1",
            requestSource = "MODEL_HINT",
            bindingGeneration = 1,
            executionGeneration = 1,
        )

        AgentRunStore(database.agentDao()).updateRunStatus(
            run = run,
            status = AgentRunStatus.CANCELLED,
            terminalReason = "USER_STOP",
        )

        assertEquals(SuspensionState.CANCELLED.name, database.agentDao().getSuspension(ticket.suspension.id)?.status)
        assertEquals("USER_STOP", database.agentDao().getSuspension(ticket.suspension.id)?.failureCode)
        assertEquals(ExecutionSlotState.FAILED.name, database.agentDao().getExecutionSlot(run.id, "slot-1")?.state)
    }

    @Test
    fun `拒绝本地落库后立即可恢复且不会履行被拒绝的工具`() = runBlocking {
        val run = insertRun("run-reject-fast")
        var adapterCalls = 0
        val activeBroker = AgentInterventionBroker(
            store,
            adapters = AgentInterventionAdapterRegistry(mapOf("acknowledgement-adapter" to RecordingAdapter({
                adapterCalls++
                AdapterDeliveryFact.DELIVERED
            }))),
        )
        val ticket = activeBroker.suspend(
            run, CapabilityRequest("server.restart.confirm", "请求操作"),
            "turn", "request", "tool", "slot", "hash", "MODEL_HINT", 1, 1,
        )
        assertTrue(activeBroker.reject(ticket.suspension.id, ticket.suspension.rowVersion))
        val rejected = requireNotNull(store.get(ticket.suspension.id))
        assertEquals(SuspensionState.READY_TO_RESUME_WITH_FAILURE.name, rejected.status)
        assertEquals("INTERVENTION_REJECTED", rejected.failureCode)
        assertFalse(activeBroker.reject(ticket.suspension.id, ticket.suspension.rowVersion))
        assertFalse(activeBroker.resolve(ticket.suspension.id, ticket.suspension.rowVersion, ticket.resolutionNonce!!))
        assertEquals(0, adapterCalls)
        assertTrue(store.claimResume(rejected.id, SuspensionState.READY_TO_RESUME_WITH_FAILURE,
            rejected.rowVersion, rejected.runGeneration, "resume-now"))
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
        val reserved = requireNotNull(database.agentDao().getCapabilityGrant("grant-1"))
        assertEquals("RESERVED", reserved.status)
        assertEquals(1, reserved.usageCount)
        val winningAttempt = listOf("attempt-1", "attempt-2").single { attempt ->
            reserved.grantUseAttemptId == attempt
        }
        assertFalse(grantStore.consume("grant-1", "wrong-attempt"))
        assertTrue(grantStore.consume("grant-1", winningAttempt))
        assertEquals("CONSUMED", database.agentDao().getCapabilityGrant("grant-1")?.status)
    }

    @Test
    fun `语义 Adapter 按绑定 claim 且模型无需持有 GrantId`() = runBlocking {
        val run = insertRun("run-grant-operation")
        val grantStore = AgentCapabilityGrantStore(database.agentDao())
        grantStore.create(
            AgentCapabilityGrantEntity(
                grantId = "internal-grant-id",
                capability = "git.push",
                runId = run.id,
                runGeneration = run.runGeneration,
                toolCallId = "tool-1",
                executionSlot = "slot-1",
                operation = "git.push",
                targetBinding = "repo-a",
                audience = "git-adapter",
                scope = "ONCE",
                issuedAt = 1,
                expiresAt = Long.MAX_VALUE,
                maxUses = 1,
                generation = 1,
            ),
        )

        assertNull(
            grantStore.claimForOperation(
                "git.push", run.id, run.runGeneration, "tool-1", "slot-1",
                "git.push", "repo-b", "git-adapter", 1,
            ),
        )
        val claim = grantStore.claimForOperation(
            "git.push", run.id, run.runGeneration, "tool-1", "slot-1",
            "git.push", "repo-a", "git-adapter", 1,
        )
        assertNotNull(claim)
        assertTrue(grantStore.consume(requireNotNull(claim)))
        assertNull(
            grantStore.claimForOperation(
                "git.push", run.id, run.runGeneration, "tool-1", "slot-1",
                "git.push", "repo-a", "git-adapter", 1,
            ),
        )
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
    fun `同进程重复投影不会轮换仍在使用的 nonce`() = runBlocking {
        val run = insertRun("run-active-nonce")
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

        val action = AgentInterventionRecovery(database.agentDao(), store)
            .recover(setOf(ticket.suspension.id))
            .single { it.suspensionId == ticket.suspension.id }
        val unchanged = requireNotNull(store.get(ticket.suspension.id))

        assertEquals("PROJECT_TO_UI", action.action)
        assertNull(action.newResolutionNonce)
        assertEquals(ticket.suspension.rowVersion, unchanged.rowVersion)
        assertTrue(store.resolve(unchanged.id, SuspensionState.WAITING_USER, unchanged.rowVersion, ticket.resolutionNonce!!,
            "stored-authorization:auth-1"))
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
    fun `同 owner 同 generation 冷启动 claim 会幂等续租`() = runBlocking {
        val run = insertRun("run-lease-renew")
        val leaseStore = AgentResourceLeaseStore(database.agentDao())
        assertTrue(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "suspension-1", "PTY", 1, run.id, run.runGeneration, 1, 100),
            ),
        )
        assertTrue(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "suspension-1", "PTY", 1, run.id, run.runGeneration, 2, 200),
            ),
        )
        assertFalse(
            leaseStore.claim(
                AgentResourceLeaseEntity("pty-1", "suspension-2", "PTY", 1, run.id, run.runGeneration, 2, 200),
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

    @Test
    fun `NONE resolution 经过真实 Adapter 后进入 READY_TO_RESUME`() = runBlocking {
        val run = insertRun("run-ack-fulfill")
        val grantStore = AgentCapabilityGrantStore(database.agentDao())
        val activeBroker = AgentInterventionBroker(store, grants = grantStore)
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("server.restart.confirm", "确认重启"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "MODEL_HINT",
            1,
            1,
        )

        assertTrue(
            activeBroker.resolve(
                ticket.suspension.id,
                ticket.suspension.rowVersion,
                ticket.resolutionNonce!!,
                ProtectedResolution.None,
            ),
        )
        assertEquals(SuspensionState.READY_TO_RESUME.name, store.get(ticket.suspension.id)?.status)
        val grantId = java.util.UUID.nameUUIDFromBytes(
            "grant|${ticket.suspension.id}|1".toByteArray(),
        ).toString()
        assertNotNull(database.agentDao().getCapabilityGrant(grantId))
    }

    @Test
    fun `DURABLE_REFERENCE 写入 Room 后由可信 Adapter 履行`() = runBlocking {
        val run = insertRun("run-durable-fulfill")
        var receivedReference: String? = null
        val adapter = RecordingAdapter(
            fulfill = { material ->
                receivedReference = (material as ProtectedResolution.DurableReference).reference
                AdapterDeliveryFact.DELIVERED
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("git-adapter" to adapter)),
            grants = AgentCapabilityGrantStore(database.agentDao()),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("git.push", "需要仓库授权"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
            "repo-a",
        )

        assertTrue(
            activeBroker.resolve(
                ticket.suspension.id,
                ticket.suspension.rowVersion,
                ticket.resolutionNonce!!,
                ProtectedResolution.DurableReference("stored-authorization:github-a"),
            ),
        )
        assertEquals("stored-authorization:github-a", receivedReference)
        assertEquals("stored-authorization:github-a", store.get(ticket.suspension.id)?.resolutionReference)
        assertEquals(SuspensionState.READY_TO_RESUME.name, store.get(ticket.suspension.id)?.status)
    }

    @Test
    fun `DURABLE_REFERENCE 拒绝把 Token 当成安全引用持久化`() {
        assertTrue(
            runCatching {
                ProtectedResolution.DurableReference("ghp_plaintext-token-must-not-be-stored")
            }.isFailure,
        )
    }

    @Test
    fun `Adapter 不可用时 EPHEMERAL 也会立即清零`() = runBlocking {
        val run = insertRun("run-missing-adapter")
        val ticket = broker.suspend(
            run,
            CapabilityRequest("ssh.connect", "密码"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        val material = ProtectedResolution.Ephemeral(charArrayOf('x'))

        assertTrue(broker.resolve(ticket.suspension.id, 0, ticket.resolutionNonce!!, material))

        assertTrue(material.borrow().all { it == '\u0000' })
        assertEquals(SuspensionState.READY_TO_RESUME_WITH_FAILURE.name, store.get(ticket.suspension.id)?.status)
        assertEquals("ADAPTER_UNAVAILABLE", store.get(ticket.suspension.id)?.failureCode)
    }

    @Test
    fun `EPHEMERAL 未投递会清零并轮换到重新输入状态`() = runBlocking {
        val run = insertRun("run-ephemeral-fulfill")
        var borrowed: CharArray? = null
        val adapter = RecordingAdapter(
            fulfill = { material ->
                borrowed = (material as ProtectedResolution.Ephemeral).borrow()
                AdapterDeliveryFact.NOT_DELIVERED
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("ssh.connect", "需要一次性密码"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )

        assertTrue(
            activeBroker.resolve(
                ticket.suspension.id,
                ticket.suspension.rowVersion,
                ticket.resolutionNonce!!,
                ProtectedResolution.Ephemeral(charArrayOf('1', '2', '3', '4')),
            ),
        )
        assertTrue(requireNotNull(borrowed).all { it == '\u0000' })
        assertEquals(SuspensionState.WAITING_USER_REENTRY.name, store.get(ticket.suspension.id)?.status)
        assertNull(store.get(ticket.suspension.id)?.resolutionReference)
    }

    @Test
    fun `Adapter 异常记录 UNKNOWN 且不会自动再次履行`() = runBlocking {
        val run = insertRun("run-unknown-fulfill")
        var calls = 0
        val adapter = RecordingAdapter(
            fulfill = {
                calls++
                error("连接在写入后断开")
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("ssh.connect", "需要一次性密码"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )

        assertTrue(
            activeBroker.resolve(
                ticket.suspension.id,
                ticket.suspension.rowVersion,
                ticket.resolutionNonce!!,
                ProtectedResolution.Ephemeral(charArrayOf('x')),
            ),
        )
        assertEquals(1, calls)
        assertEquals(SuspensionState.USER_DECISION_REQUIRED.name, store.get(ticket.suspension.id)?.status)
        assertNull(activeBroker.fulfill(ticket.suspension.id))
        assertEquals(1, calls)
        val unknown = requireNotNull(store.get(ticket.suspension.id))
        assertTrue(activeBroker.continueAfterUnknown(unknown.id, unknown.rowVersion))
        assertEquals(SuspensionState.WAITING_USER_REENTRY.name, store.get(unknown.id)?.status)
        assertTrue(store.get(unknown.id)?.resolutionNonceHash?.isNotBlank() == true)
    }

    @Test
    fun `READY_TO_RESUME 并发 claim 只恢复一个 slot`() = runBlocking {
        val run = insertRun("run-resume-claim")
        val activeBroker = AgentInterventionBroker(
            store = store,
            grants = AgentCapabilityGrantStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("server.restart.confirm", "确认"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "MODEL_HINT",
            1,
            1,
        )
        assertTrue(activeBroker.resolve(ticket.suspension.id, 0, ticket.resolutionNonce!!))
        val ready = requireNotNull(store.get(ticket.suspension.id))

        val claims = listOf("resume-a", "resume-b").map { attempt ->
            async {
                store.claimResume(
                    ready.id,
                    SuspensionState.READY_TO_RESUME,
                    ready.rowVersion,
                    ready.runGeneration,
                    attempt,
                )
            }
        }.awaitAll()

        assertEquals(1, claims.count { it })
        val resuming = requireNotNull(store.get(ready.id))
        assertEquals(SuspensionState.RESUMING.name, resuming.status)
        assertEquals(ExecutionSlotState.RESUMING.name, database.agentDao().getExecutionSlot(run.id, "slot-1")?.state)
        assertTrue(store.finishResume(resuming.id, resuming.rowVersion))
        assertEquals(SuspensionState.RESUMED.name, store.get(ready.id)?.status)
        assertEquals(ExecutionSlotState.COMPLETED.name, database.agentDao().getExecutionSlot(run.id, "slot-1")?.state)
    }

    @Test
    fun `DURABLE_REFERENCE 在 RESOLUTION_RECEIVED 冷启动后继续 fulfillment`() = runBlocking {
        val run = insertRun("run-durable-recovery")
        var fulfillmentCalls = 0
        val adapter = RecordingAdapter(
            fulfill = {
                fulfillmentCalls++
                AdapterDeliveryFact.DELIVERED
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("git-adapter" to adapter)),
            grants = AgentCapabilityGrantStore(database.agentDao()),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("git.push", "授权"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        assertTrue(
            store.resolve(
                ticket.suspension.id,
                SuspensionState.WAITING_USER,
                0,
                ticket.resolutionNonce!!,
                "stored-authorization:github-a",
            ),
        )

        val actions = AgentInterventionRecovery(
            database.agentDao(),
            store,
            broker = activeBroker,
        ).recover()

        assertEquals(1, fulfillmentCalls)
        assertTrue(actions.any { it.suspensionId == ticket.suspension.id && it.action == "FULFILLMENT_DELIVERED" })
        assertEquals(SuspensionState.READY_TO_RESUME.name, store.get(ticket.suspension.id)?.status)
    }

    @Test
    fun `Run 在 FULFILLING 中取消只记录 delivery fact 不恢复`() = runBlocking {
        val run = insertRun("run-cancel-during-fulfill")
        val adapter = RecordingAdapter(
            fulfill = {
                database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)
                AdapterDeliveryFact.DELIVERED
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
            grants = AgentCapabilityGrantStore(database.agentDao()),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("ssh.connect", "密码"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )

        assertTrue(
            activeBroker.resolve(
                ticket.suspension.id,
                0,
                ticket.resolutionNonce!!,
                ProtectedResolution.Ephemeral(charArrayOf('x')),
            ),
        )

        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(run.id)?.status)
        assertEquals(SuspensionState.DELIVERED.name, store.get(ticket.suspension.id)?.status)
        assertNull(database.agentDao().getCapabilityGrant(
            java.util.UUID.nameUUIDFromBytes("grant|${ticket.suspension.id}|1".toByteArray()).toString(),
        ))
    }

    @Test
    fun `冷启动遇到 terminal Run 的 FULFILLING 仍会 reconcile`() = runBlocking {
        val run = insertRun("run-terminal-reconcile")
        val adapter = RecordingAdapter(
            fulfill = { AdapterDeliveryFact.UNKNOWN },
            reconciliation = AdapterDeliveryFact.DELIVERED,
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        val ticket = activeBroker.suspend(
            run,
            CapabilityRequest("ssh.connect", "密码"),
            "turn-1",
            "request-1",
            "tool-1",
            "slot-1",
            "hash-1",
            "EXECUTOR_PROVEN",
            1,
            1,
        )
        assertTrue(store.resolve(ticket.suspension.id, SuspensionState.WAITING_USER, 0, ticket.resolutionNonce!!))
        assertTrue(store.claimFulfillment(ticket.suspension.id, 1, run.runGeneration, "attempt-1"))
        val fulfilling = requireNotNull(store.get(ticket.suspension.id))
        assertTrue(
            AgentResourceLeaseStore(database.agentDao()).claim(
                AgentResourceLeaseEntity(
                    resourceRef = fulfilling.targetBindingRef,
                    leaseOwner = fulfilling.id,
                    leaseKind = "SSH_CONNECTION",
                    leaseGeneration = fulfilling.resourceEpoch,
                    runId = run.id,
                    runGeneration = run.runGeneration,
                    issuedAt = 1,
                    expiresAt = Long.MAX_VALUE,
                ),
            ),
        )
        database.agentDao().cancelActiveRunById(run.id, "USER_STOP", 2)

        val actions = AgentInterventionRecovery(
            database.agentDao(),
            store,
            broker = activeBroker,
        ).recover()

        assertTrue(actions.any {
            it.suspensionId == ticket.suspension.id && it.action == "TERMINAL_RECONCILIATION_DELIVERED"
        })
        assertEquals(SuspensionState.DELIVERED.name, store.get(ticket.suspension.id)?.status)
    }

    @Test
    fun `FULFILLING 冷启动严格区分三种 reconcile 结果`() = runBlocking {
        val expectedStates = mapOf(
            AdapterDeliveryFact.DELIVERED to SuspensionState.READY_TO_RESUME,
            AdapterDeliveryFact.NOT_DELIVERED to SuspensionState.WAITING_USER_REENTRY,
            AdapterDeliveryFact.UNKNOWN to SuspensionState.USER_DECISION_REQUIRED,
        )
        expectedStates.forEach { (fact, expectedState) ->
            val suffix = fact.name.lowercase()
            val run = insertRun("run-reconcile-$suffix")
            val adapter = RecordingAdapter(
                fulfill = { AdapterDeliveryFact.UNKNOWN },
                reconciliation = fact,
            )
            val activeBroker = AgentInterventionBroker(
                store = store,
                adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
                grants = AgentCapabilityGrantStore(database.agentDao()),
                resourceLeases = AgentResourceLeaseStore(database.agentDao()),
            )
            val ticket = activeBroker.suspend(
                run,
                CapabilityRequest("ssh.connect", "密码"),
                "turn-$suffix",
                "request-$suffix",
                "tool-$suffix",
                "slot-$suffix",
                "hash-$suffix",
                "EXECUTOR_PROVEN",
                1,
                1,
            )
            assertTrue(store.resolve(ticket.suspension.id, SuspensionState.WAITING_USER, 0, ticket.resolutionNonce!!))
            assertTrue(store.claimFulfillment(ticket.suspension.id, 1, run.runGeneration, "attempt-$suffix"))

            val actions = AgentInterventionRecovery(
                database.agentDao(),
                store,
                broker = activeBroker,
            ).recover()

            assertTrue(actions.any {
                it.suspensionId == ticket.suspension.id && it.action == "RECONCILIATION_${fact.name}"
            })
            assertEquals(expectedState.name, store.get(ticket.suspension.id)?.status)
        }
    }

    @Test
    fun `两个 slot 竞争同一 Adapter target 只有 lease owner 能履行`() = runBlocking {
        val firstRun = insertRun("run-resource-first")
        val secondRun = insertRun("run-resource-second")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val adapter = RecordingAdapter(
            fulfill = {
                calls++
                entered.complete(Unit)
                release.await()
                AdapterDeliveryFact.DELIVERED
            },
        )
        val activeBroker = AgentInterventionBroker(
            store = store,
            adapters = AgentInterventionAdapterRegistry(mapOf("ssh-adapter" to adapter)),
            resourceLeases = AgentResourceLeaseStore(database.agentDao()),
        )
        suspend fun ticket(run: AgentRunEntity, suffix: String) = activeBroker.suspend(
            run,
            CapabilityRequest("ssh.connect", "密码"),
            "turn-$suffix",
            "request-$suffix",
            "tool-$suffix",
            "slot-$suffix",
            "hash-$suffix",
            "EXECUTOR_PROVEN",
            0,
            0,
            "ssh-target-a",
        )
        val first = ticket(firstRun, "first")
        val second = ticket(secondRun, "second")
        val firstResolve = async {
            activeBroker.resolve(
                first.suspension.id,
                0,
                first.resolutionNonce!!,
                ProtectedResolution.Ephemeral(charArrayOf('a')),
            )
        }
        entered.await()

        assertTrue(
            activeBroker.resolve(
                second.suspension.id,
                0,
                second.resolutionNonce!!,
                ProtectedResolution.Ephemeral(charArrayOf('b')),
            ),
        )
        release.complete(Unit)
        assertTrue(firstResolve.await())

        assertEquals(1, calls)
        assertEquals(SuspensionState.READY_TO_RESUME.name, store.get(first.suspension.id)?.status)
        assertEquals(SuspensionState.READY_TO_RESUME_WITH_FAILURE.name, store.get(second.suspension.id)?.status)
        assertEquals("RESOURCE_LEASE_UNAVAILABLE", store.get(second.suspension.id)?.failureCode)
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

    private class RecordingAdapter(
        private val fulfill: suspend (ProtectedResolution) -> AdapterDeliveryFact,
        private val reconciliation: AdapterDeliveryFact = AdapterDeliveryFact.UNKNOWN,
    ) : AgentInterventionAdapter {
        override suspend fun validate(request: TrustedInterventionRequest): Boolean = true
        override suspend fun present(request: TrustedInterventionRequest): String = request.capabilityId
        override suspend fun fulfill(
            request: TrustedInterventionRequest,
            protectedResolution: ProtectedResolution,
        ): AdapterFulfillmentResult = AdapterFulfillmentResult(fulfill(protectedResolution))

        override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact = reconciliation
        override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
    }
}
