package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareComputerConfigEntity
import com.android.everytalk.data.database.entities.CloudflareAuthorizationEntity
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.just
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

class CloudflareComputerManagerTest {
    @Test
    fun `Cloudflare 默认能力不包含本地 Shell`() {
        assertEquals(false, CloudflareComputerProviderContract.supports(ComputerCapability.LOCAL_SHELL_EXECUTE))
        assertEquals(true, CloudflareComputerProviderContract.supports(ComputerCapability.WORKER_DEPLOY))
    }

    @Test
    fun `切换 Account 使用 API 名称和原授权 generation 并清理临时凭据`() = runTest {
        fixture(testScheduler).use { f ->
            val account = f.manager.switchAccount("computer", "company")
            assertEquals("公司", account.name)
            assertTrue(f.payload.all { it == '\u0000' })
            coVerify(exactly = 1) { f.dao.switchCloudflareAccountIfCurrent("computer", "personal", "auth", 7, "company", "公司", any()) }
        }
    }

    @Test
    fun `模型填写的未知 Account 不写入绑定`() = runTest {
        fixture(testScheduler).use { f ->
            assertTrue(runCatching { f.manager.switchAccount("computer", "unrelated") }.exceptionOrNull() is IllegalArgumentException)
            coVerify(exactly = 0) { f.dao.switchCloudflareAccountIfCurrent(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `网络返回后授权变化则拒绝提交切换`() = runTest {
        fixture(testScheduler, commitResult = 0).use { f ->
            assertTrue(runCatching { f.manager.switchAccount("computer", "company") }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun `已退出的身份不会读取 Token 或发起 Account 查询`() = runTest {
        fixture(testScheduler, revoked = true).use { f ->
            assertTrue(runCatching { f.manager.listAccountsForComputer("computer") }.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { f.credentials.loadAgentAuthorization(any()) }
        }
    }

    @Test
    fun `创建 Computer 前再次校验 Account 属于当前授权`() = runTest {
        fixture(testScheduler).use { f ->
            coEvery { f.dao.saveCloudflareComputer(any(), any(), any()) } just Runs
            coEvery { f.credentials.saveAgentAuthorization(any(), any()) } just Runs
            coEvery { f.credentials.deleteAgentAuthorization(any()) } just Runs
            val result = f.manager.createComputer(
                displayName = "公司 Cloudflare",
                tokenResult = CloudflareTokenExchangeResult("test-token".toCharArray(), null, setOf("account:read"), null),
                account = CloudflareApiAccount("company", "公司"),
            )
            assertEquals(ComputerProvider.CLOUDFLARE, result.provider)
            coVerify(exactly = 1) { f.dao.saveCloudflareComputer(any(), any(), any()) }
        }
    }

    @Test
    fun `创建时拒绝未知 Account 且不遗留授权`() = runTest {
        fixture(testScheduler).use { f ->
            coEvery { f.credentials.saveAgentAuthorization(any(), any()) } just Runs
            coEvery { f.credentials.deleteAgentAuthorization(any()) } just Runs
            val token = "test-token".toCharArray()
            assertTrue(runCatching {
                f.manager.createComputer("Cloudflare", CloudflareTokenExchangeResult(token, null, emptySet(), null), CloudflareApiAccount("unknown", "未知"))
            }.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { f.dao.saveCloudflareComputer(any(), any(), any()) }
            coVerify(exactly = 1) { f.credentials.deleteAgentAuthorization(any()) }
            assertTrue(token.all { it == '\u0000' })
        }
    }

    @Test
    fun `Cloudflare 授权退出状态可被模型识别`() {
        assertEquals(ComputerStatus.AUTHORIZATION_REQUIRED, ComputerStatus.valueOf("AUTHORIZATION_REQUIRED"))
    }

    private fun fixture(scheduler: TestCoroutineScheduler, commitResult: Int = 1, revoked: Boolean = false): Fixture {
        val dao = mockk<ComputerDao>()
        val credentials = mockk<ComputerCredentialStore>()
        val payload = "{\"access_token\":\"test-token\"}".toCharArray()
        coEvery { dao.getCloudflareConfig("computer") } returns CloudflareComputerConfigEntity("computer", "auth", "personal", "个人", "[]")
        coEvery { dao.getCloudflareAuthorization("auth") } returns CloudflareAuthorizationEntity("auth", "ref", "[]", 0, null, revoked, 7)
        coEvery { dao.getLatestCloudflareWorkerHealth("computer") } returns null
        coEvery { credentials.loadAgentAuthorization("ref") } returns payload
        coEvery { dao.switchCloudflareAccountIfCurrent(any(), any(), any(), any(), any(), any(), any()) } returns commitResult
        val client = HttpClient(MockEngine(io.ktor.client.engine.mock.MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scheduler)
            addHandler { request ->
                assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
                respond("{\"success\":true,\"result\":[{\"id\":\"company\",\"name\":\"公司\"}]}", headers = headersOf("Content-Type", "application/json"))
            }
        }))
        return Fixture(dao, credentials, payload, client, CloudflareComputerManager(dao, credentials, CloudflareApiClient(client, { "unused" })))
    }

    private data class Fixture(
        val dao: ComputerDao, val credentials: ComputerCredentialStore, val payload: CharArray,
        val client: HttpClient, val manager: CloudflareComputerManager,
    ) : AutoCloseable {
        override fun close() = client.close()
    }
}
