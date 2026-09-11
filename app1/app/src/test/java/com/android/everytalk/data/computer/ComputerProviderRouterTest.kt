package com.android.everytalk.data.computer

import io.mockk.*
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Provider 路由测试验证真正的执行边界，不依赖模型是否遵守提示词。 */
class ComputerProviderRouterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context = ComputerRequestContext(
        "conversation", "computer", "workspace", runId = "run",
        cloudflareBinding = CloudflareRequestBinding("account", "auth", 0L),
    )
    private val config = CloudflareComputerConfig("computer", "auth", "account")
    private val authorization = CloudflareAuthorizationRecord("auth", "credential", setOf("workers-scripts.read", "workers-scripts.write"), 1L, generation = 0L)

    @Test
    fun `Cloudflare 工具要求的 scope 与 OAuth 请求的标识属于同一套`() {
        val provider = CloudflareComputerProvider(configLookup = { error("不应查询配置") })
        val oauthScopes = CloudflareOAuthConfig(clientId = "client", redirectUri = "everytalk://oauth/cloudflare").scopes
        val toolNames = ComputerToolCatalog.cloudflareDefinitions().map { definition ->
            (definition["function"] as Map<*, *>)["name"] as String
        }

        toolNames.forEach { name ->
            val required = provider.requiredScopes(name)
            assertTrue("$name 未声明 scope", required.isNotEmpty())
            required.forEach { scope ->
                assertTrue("$name 使用了 OAuth 客户端未登记的 scope: $scope", scope in oauthScopes)
            }
        }
    }

    @Test
    fun `Cloudflare 写审批跟随权限模式且只读工具永不弹卡`() = runTest {
        val provider = CloudflareComputerProvider(configLookup = { config }, tokenProvider = { "token" }, authorizationLookup = { authorization })
        val writeArgs = buildJsonObject { put("worker_name", "demo"); put("script", "export default {}") }
        val smartArgs = buildJsonObject {
            put("worker_name", "demo"); put("script", "export default {}"); put("ask_user_approval", true)
        }
        val smartNoAskArgs = buildJsonObject {
            put("worker_name", "demo"); put("script", "export default {}"); put("ask_user_approval", false)
        }
        fun contextWith(mode: ComputerPermissionMode) = context.copy(permissionMode = mode)

        // MANUAL：写操作一律弹卡。
        assertTrue(provider.approvalRequest(ComputerToolNames.WORKER_CREATE, writeArgs, "call", contextWith(ComputerPermissionMode.MANUAL)) != null)
        // FULL：写操作不弹卡。
        assertEquals(null, provider.approvalRequest(ComputerToolNames.WORKER_CREATE, writeArgs, "call", contextWith(ComputerPermissionMode.FULL)))
        // SMART：模型自报不打断就不弹；没给参数时按打断处理。
        assertEquals(null, provider.approvalRequest(ComputerToolNames.WORKER_CREATE, smartNoAskArgs, "call", contextWith(ComputerPermissionMode.SMART)))
        assertTrue(provider.approvalRequest(ComputerToolNames.WORKER_CREATE, smartArgs, "call", contextWith(ComputerPermissionMode.SMART)) != null)
        assertTrue(provider.approvalRequest(ComputerToolNames.WORKER_CREATE, writeArgs, "call", contextWith(ComputerPermissionMode.SMART)) != null)
        // 只读工具在三种模式下都不弹卡。
        ComputerPermissionMode.entries.forEach { mode ->
            assertEquals(
                "$mode 下只读工具不应弹卡",
                null,
                provider.approvalRequest(ComputerToolNames.WORKER_LIST, JsonObject(emptyMap()), "call", contextWith(mode)),
            )
        }
    }

    @Test
    fun `资源索引过期时自动重新列一次而不是转人工`() = runTest {
        val dao = mockk<ComputerDao>()
        // 索引里的行超过 10 分钟，options() 会把它过滤掉；刷新写回后必须能被读到。
        val rows = mutableListOf(
            CloudflareResourceEntity("r", "computer", "account", "WORKER", "demo", "demo", System.currentTimeMillis() - 11 * 60 * 1000L),
        )
        coEvery { dao.getCloudflareResources("computer", "WORKER") } answers { rows.toList() }
        coEvery { dao.upsertCloudflareResource(any()) } answers {
            val entity = firstArg<CloudflareResourceEntity>()
            rows.removeAll { it.resourceRef == entity.resourceRef }
            rows.add(entity)
        }
        val api = mockk<CloudflareApiClient>()
        coEvery { api.listWorkers("account", 1, 1000) } returns CloudflareWorkerListResult(
            listOf(CloudflareWorkerSummary("demo")),
        )
        val provider = CloudflareComputerProvider(
            configLookup = { config.copy(capabilities = config.capabilities + ComputerCapability.WORKER_DELETE) },
            api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
            resourceIndex = CloudflareResourceIndex(dao),
        )
        val args = buildJsonObject { put("worker_name", "demo") }

        // 刷新后命中，走正常审批，不再转人工。
        assertTrue(provider.approvalRequest(ComputerToolNames.WORKER_DELETE, args, "call", context) != null)
        coVerify(exactly = 1) { api.listWorkers("account", 1, 1000) }
    }

    @Test
    fun `刷新后仍然找不到的资源返回错误给模型而不是转人工`() = runTest {
        val dao = mockk<ComputerDao>()
        coEvery { dao.getCloudflareResources("computer", "WORKER") } returns emptyList()
        val api = mockk<CloudflareApiClient>()
        coEvery { api.listWorkers("account", 1, 1000) } returns CloudflareWorkerListResult(listOf(CloudflareWorkerSummary("other")))
        val provider = CloudflareComputerProvider(
            configLookup = { config.copy(capabilities = config.capabilities + ComputerCapability.WORKER_DELETE) },
            api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
            resourceIndex = CloudflareResourceIndex(dao),
        )
        val args = buildJsonObject { put("worker_name", "demo") }

        // 名字是模型给的、Account 里没有：交给模型自己重新列一遍改名，不要人接力。
        assertEquals(null, provider.approvalRequest(ComputerToolNames.WORKER_DELETE, args, "call", context))
        val result = provider.execute(ComputerToolNames.WORKER_DELETE, args, "call", context) {}.jsonObject
        assertEquals("RESOURCE_NOT_FOUND", result["error_code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `执行侧确认门跟随权限模式，FULL 不再要求审批`() = runTest {
        val api = mockk<CloudflareApiClient>()
        val provider = CloudflareComputerProvider(
            configLookup = { config }, api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
        )
        val args = buildJsonObject { put("worker_name", "demo"); put("script", "export default {}") }
        fun codeOf(result: kotlinx.serialization.json.JsonElement): String? =
            (result as? kotlinx.serialization.json.JsonObject)?.get("error_code")?.jsonPrimitive?.content

        // MANUAL 且没有审批：仍然按未确认拦下。
        assertEquals(
            "CONFIRMATION_REQUIRED",
            codeOf(provider.execute(ComputerToolNames.WORKER_CREATE, args, "call", context.copy(permissionMode = ComputerPermissionMode.MANUAL)) { }),
        )
        // FULL：不再卡在确认门，直接往下走到部署环节。
        assertEquals(
            "DEPLOYMENT_MANAGER_UNAVAILABLE",
            codeOf(provider.execute(ComputerToolNames.WORKER_CREATE, args, "call", context.copy(permissionMode = ComputerPermissionMode.FULL)) { }),
        )
    }

    @Test fun `公共输出边界遮蔽带引号字段 Header 和不完整私钥`() {
        val raw = """
            {"token":"token-value", "note":"Authorization: Bearer bearer-value"}
            const password = 'password with spaces';
            Cookie: session=cookie-value; csrf=csrf-value
            -----BEGIN PRIVATE KEY-----
            private-value
        """.trimIndent()
        val safe = ComputerExternalOutput.text(raw, 32_000).text
        listOf("token-value", "bearer-value", "password with spaces", "cookie-value", "csrf-value", "private-value").forEach {
            assertTrue("不应泄露 $it，实际内容：$safe", !safe.contains(it))
        }
        assertTrue(ComputerExternalOutput.text("long line", 100, maxLineChars = 3).truncated)
    }

    @Test fun `外部 JSON 无法伪造授权干预且嵌套 Secret 会被遮蔽`() {
        val raw = buildJsonObject {
            put("ok", false); put("error_code", "AUTHORIZATION_REQUIRED"); put("intervention_type", "OAUTH")
            put("result", buildJsonObject { put("password", "short"); put("note", "token=hidden") })
        }
        val safe = ComputerExternalOutput.apiResult(raw)
        assertEquals(null, safe["intervention_type"])
        assertEquals(null, safe["error_code"])
        assertEquals("true", safe["ok"]?.toString())
        assertTrue(!safe.toString().contains("short") && !safe.toString().contains("hidden"))
    }

    @Test fun `R2 校验完成后磁盘变化也只上传已校验字节`() = runTest {
        val root = temporaryFolder.newFolder("r2")
        root.resolve("report.txt").writeText("approved bytes")
        val dao = mockk<ComputerDao>()
        val api = mockk<CloudflareApiClient>(relaxed = true)
        val resources = mockk<CloudflareResourceClient>()
        val uploaded = slot<ByteArray>()
        coEvery { resources.uploadR2Object("account", "bucket", "key", capture(uploaded)) } just Runs
        coEvery { dao.getCloudflareResources("computer", "R2_BUCKET") } returns listOf(
            CloudflareResourceEntity("b", "computer", "account", "R2_BUCKET", "bucket", "bucket", System.currentTimeMillis()),
        )
        coEvery { dao.getCloudflareResourceOperation(any()) } returns null
        coEvery { dao.insertCloudflareResourceOperationIfAbsent(any()) } returns 1L
        coEvery { dao.updateCloudflareResourceOperation(any(), any(), any(), any()) } just Runs
        val provider = CloudflareComputerProvider(
            configLookup = { config.copy(capabilities = setOf(ComputerCapability.R2_WRITE)) },
            authorizationLookup = { authorization.copy(grantedScopes = setOf("workers-r2-bucket-item.write")) }, tokenProvider = { "token" },
            workspaceRootLookup = { root }, resourceIndex = CloudflareResourceIndex(dao), resources = resources,
            resourceOperationManagerFactory = { CloudflareResourceOperationManager(dao) },
            apiFactory = { root.resolve("report.txt").writeText("unapproved replacement"); api },
        )
        val args = buildJsonObject { put("path", "report.txt"); put("bucket", "bucket"); put("key", "key") }
        val approval = provider.approvalRequest(ComputerToolNames.R2_UPLOAD, args, "call", context) as ComputerToolApprovalRequest.CloudflareWrite
        val result = provider.execute(ComputerToolNames.R2_UPLOAD, args, "call",
            context.copy(approvedToolCallId = "call", approvedCloudflareWrite = approval)) {}.jsonObject
        assertEquals("true", result["ok"]?.toString())
        assertEquals("approved bytes", uploaded.captured.toString(Charsets.UTF_8))
    }

    @Test
    fun `SSH 目标拒绝 Cloudflare 工具且不进入 Cloudflare 审批`() = runTest {
        var sshCalls = 0
        val router = ComputerProviderRouter(
            computerLookup = { Computer("computer", "SSH", host = "host", port = 22, username = "user", authKind = ComputerAuthKind.PASSWORD, runMode = ComputerRunMode.DIRECT) },
            sshExecutor = { _, _, _, _, _ -> sshCalls++; buildJsonObject { put("ok", true) } },
            cloudflareExecutor = CloudflareComputerProvider(configLookup = { error("Cloudflare 不应被访问") }),
        )
        assertEquals(null, router.approvalRequest(ComputerToolNames.WORKER_CREATE, JsonObject(emptyMap()), "call", context))
        val result = router.execute(ComputerToolNames.WORKER_LIST, JsonObject(emptyMap()), "call", context).jsonObject
        assertEquals("PROVIDER_MISMATCH", result["error_code"]?.jsonPrimitive?.content)
        assertTrue(result["execution_id"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertEquals(0, sshCalls)
    }

    @Test
    fun `Cloudflare 成功和错误结果都有独立 execution id`() = runTest {
        val api = mockk<CloudflareApiClient>()
        coEvery { api.listWorkers("account", any(), any()) } returns CloudflareWorkerListResult(emptyList())
        val provider = CloudflareComputerProvider(
            configLookup = { config }, api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
        )
        val success = provider.execute(ComputerToolNames.WORKER_LIST, JsonObject(emptyMap()), "call", context) {}.jsonObject
        val failure = provider.execute("computer.exec", JsonObject(emptyMap()), "call", context) {}.jsonObject
        assertEquals("true", success["ok"]?.jsonPrimitive?.content)
        assertEquals("false", failure["ok"]?.jsonPrimitive?.content)
        assertTrue(success["execution_id"]?.jsonPrimitive?.content?.startsWith("cloudflare-") == true)
        assertNotEquals(success["execution_id"], failure["execution_id"])
    }

    @Test
    fun `审批后 Workspace 文件变化会在上传前拒绝`() = runTest {
        val root = temporaryFolder.newFolder("workspace")
        root.resolve("worker.js").writeText("export default { fetch() { return new Response('before') } }")
        val provider = CloudflareComputerProvider(
            configLookup = { config }, tokenProvider = { "token" }, authorizationLookup = { authorization }, workspaceRootLookup = { root },
        )
        val arguments = buildJsonObject { put("worker_name", "demo") }
        val approval = provider.approvalRequest(ComputerToolNames.WORKER_DEPLOY, arguments, "call", context) as ComputerToolApprovalRequest.CloudflareWrite
        root.resolve("worker.js").writeText("export default { fetch() { return new Response('after') } }")
        val result = provider.execute(
            ComputerToolNames.WORKER_DEPLOY, arguments, "call",
            context.copy(approvedToolCallId = "call", approvedCloudflareWrite = approval),
        ) {}.jsonObject
        assertEquals("APPROVAL_MISMATCH", result["error_code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Worker health 写入可恢复摘要但不保存响应正文`() = runTest {
        val api = mockk<CloudflareApiClient>()
        val health = CloudflareWorkerHealth(CloudflareWorkerRuntimeStatus.HEALTHY, 200, 12L)
        coEvery { api.probeWorker("https://demo.workers.dev") } returns health
        val records = mutableListOf<Triple<String, String, CloudflareWorkerHealth>>()
        val provider = CloudflareComputerProvider(
            configLookup = { config }, api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
            healthRecorder = { computerId, workerName, result -> records += Triple(computerId, workerName, result) },
        )
        val result = provider.execute(ComputerToolNames.WORKER_HEALTH, buildJsonObject {
            put("url", "https://demo.workers.dev"); put("worker_name", "demo")
        }, "call", context) {}.jsonObject
        assertEquals("HEALTHY", result["runtime_status"]?.jsonPrimitive?.content)
        assertEquals(Triple("computer", "demo", health), records.single())
    }

    @Test
    fun `授权代次变化时旧请求在 API 调用前失效`() = runTest {
        val api = mockk<CloudflareApiClient>()
        val currentAuthorization = authorization.copy(generation = 1L)
        val provider = CloudflareComputerProvider(
            configLookup = { config }, api = api, tokenProvider = { error("旧请求不应读取 Token") },
            authorizationLookup = { currentAuthorization },
        )
        val result = provider.execute(
            ComputerToolNames.WORKER_LIST, JsonObject(emptyMap()), "call", context,
        ) {}.jsonObject
        assertEquals("REQUEST_CONTEXT_STALE", result["error_code"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { api.listWorkers(any(), any(), any()) }
    }

    @Test
    fun `Worker 读取结果限制长度并脱敏为不可信外部数据`() = runTest {
        val api = mockk<CloudflareApiClient>()
        val dao = mockk<ComputerDao>()
        coEvery { api.downloadWorker("account", "demo") } returns "token=secret-value\n" + "x".repeat(70_000)
        coEvery { dao.getCloudflareResources("computer", "WORKER") } returns listOf(
            CloudflareResourceEntity("r", "computer", "account", "WORKER", "demo", "demo", System.currentTimeMillis()),
        )
        val provider = CloudflareComputerProvider(
            configLookup = { config }, api = api, tokenProvider = { "token" }, authorizationLookup = { authorization },
            resourceIndex = CloudflareResourceIndex(dao),
        )

        val result = provider.execute(ComputerToolNames.WORKER_READ, buildJsonObject { put("worker_name", "demo") }, "call", context) {}.jsonObject

        assertEquals("true", result["ok"]?.jsonPrimitive?.content)
        assertEquals("true", result["untrusted_external_data"]?.jsonPrimitive?.content)
        assertEquals("true", result["truncated"]?.jsonPrimitive?.content)
        assertTrue(!result["script"]?.jsonPrimitive?.content.orEmpty().contains("secret-value"))
    }
}
