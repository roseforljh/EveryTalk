package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 资源选择测试：Account、Computer 和资源类型必须同时匹配。 */
class CloudflareResourceIndexTest {
    @Test
    fun `列表响应写入可信索引且未知资源被拒绝`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val index = CloudflareResourceIndex(dao, clock = { 1_000L })
        coEvery { dao.upsertCloudflareResource(any()) } just Runs
        coEvery { dao.getCloudflareResources("computer", "D1") } returns listOf(
            CloudflareResourceEntity("r", "computer", "account", "D1", "db-1", "测试库", 1_000L),
        )
        index.rememberPage(
            "computer", "account", "D1",
            Json.parseToJsonElement("""{"result":[{"uuid":"db-1","name":"测试库"}]}""").jsonObject,
        )
        index.requireKnown("computer", "account", "D1", "db-1")
        assertThrows(CloudflareApiException::class.java) {
            runBlocking { index.requireKnown("computer", "other-account", "D1", "db-1") }
        }
        coVerify { dao.upsertCloudflareResource(match { it.accountId == "account" && it.resourceId == "db-1" }) }
    }

    @Test
    fun `过期索引必须重新列出`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val index = CloudflareResourceIndex(dao, clock = { 700_001L })
        coEvery { dao.getCloudflareResources("computer", "R2_BUCKET") } returns listOf(
            CloudflareResourceEntity("r", "computer", "account", "R2_BUCKET", "bucket", "bucket", 1L),
        )
        assertEquals(emptyList<ResourceOption>(), index.options("computer", "account", "R2_BUCKET"))
        assertThrows(CloudflareApiException::class.java) {
            runBlocking { index.requireKnown("computer", "account", "R2_BUCKET", "bucket") }
        }
        Unit
    }

    @Test
    fun `R2 嵌套 bucket 列表和 Queue 专用字段按真实协议解析`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val saved = mutableListOf<CloudflareResourceEntity>()
        coEvery { dao.upsertCloudflareResource(capture(saved)) } just Runs
        val index = CloudflareResourceIndex(dao)
        index.rememberPage("c1", "a", "R2_BUCKET", Json.parseToJsonElement("""{"result":{"buckets":[{"name":"bucket"}]}}""").jsonObject)
        index.rememberPage("c1", "a", "QUEUE", Json.parseToJsonElement("""{"result":[{"queue_id":"q1","queue_name":"queue"}]}""").jsonObject)
        index.rememberPage("c2", "a", "QUEUE", Json.parseToJsonElement("""{"result":[{"queue_id":"q1","queue_name":"queue"}]}""").jsonObject)
        assertEquals(listOf("bucket", "q1", "q1"), saved.map { it.resourceId })
        assertEquals(3, saved.map { it.resourceRef }.toSet().size)
    }

    @Test
    fun `Worker binding 不能用展示名代替真实资源 ID`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val index = CloudflareResourceIndex(dao, clock = { 1_000L })
        coEvery { dao.getCloudflareResources("c1", "D1") } returns listOf(
            CloudflareResourceEntity("r", "c1", "a1", "D1", "uuid-1", "业务数据库", 1_000L),
        )
        assertThrows(CloudflareApiException::class.java) {
            runBlocking { index.requireKnownBinding("c1", "a1", "D1", "业务数据库") }
        }
        index.requireKnownBinding("c1", "a1", "D1", "uuid-1")
    }
}
