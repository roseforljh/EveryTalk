package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** UNKNOWN 与明确拒绝必须分开，但两者都不能在恢复时盲目重放 SQL。 */
class D1MigrationManagerTest {
    @Test
    fun `权限拒绝记录失败并保留原错误码`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val resources = mockk<CloudflareResourceClient>()
        coEvery { dao.insertCloudflareResourceIfAbsent(any()) } returns 1L
        coEvery { dao.completeD1Migration(any(), any(), any()) } returns 1
        coEvery { resources.runD1Migration(any(), any(), any()) } throws CloudflareApiException("PERMISSION_DENIED", "拒绝")
        val failure = assertThrows(CloudflareApiException::class.java) {
            runBlocking { D1MigrationManager(dao, resources).execute("c", "a", "db", "CREATE TABLE t(id INTEGER)") }
        }
        assertEquals("PERMISSION_DENIED", failure.code)
        coVerify { dao.completeD1Migration(any(), "D1_MIGRATION_FAILED", any()) }
    }

    @Test
    fun `批处理单条失败仍为未知且相同 migration 不重发`() = runBlocking {
        val dao = mockk<ComputerDao>()
        val resources = mockk<CloudflareResourceClient>()
        val records = mutableListOf<CloudflareResourceEntity>()
        coEvery { dao.insertCloudflareResourceIfAbsent(capture(records)) } returnsMany listOf(1L, -1L)
        coEvery { dao.getCloudflareResource(any()) } answers { records.first() }
        coEvery { resources.runD1Migration(any(), any(), any()) } throws CloudflareApiException("D1_QUERY_FAILED", "部分失败")
        val manager = D1MigrationManager(dao, resources)
        repeat(2) {
            val failure = assertThrows(CloudflareApiException::class.java) {
                runBlocking { manager.execute("c", "a", "db", "CREATE TABLE t(id INTEGER); INSERT INTO missing VALUES(1)") }
            }
            assertEquals("RESULT_UNKNOWN", failure.code)
        }
        coVerify(exactly = 1) { resources.runD1Migration(any(), any(), any()) }
        coVerify(exactly = 0) { dao.completeD1Migration(any(), any(), any()) }
    }
}
