package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareResourceOperationEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Cloudflare 写操作账本的边界测试。
 * 这些测试不调用网络，只验证“是否允许再次发送”这一数据完整性规则。
 */
class CloudflareResourceOperationManagerTest {
    @Test
    fun `同一可信操作第二次调用不会再次执行 action`() = runTest {
        val dao = mockk<ComputerDao>()
        val manager = CloudflareResourceOperationManager(dao)
        val inserted = slotEntity()
        coEvery { dao.getCloudflareResourceOperation(any()) } returns null
        coEvery { dao.insertCloudflareResourceOperationIfAbsent(capture(inserted)) } returns 1L
        coEvery { dao.updateCloudflareResourceOperation(any(), any(), any(), any()) } just Runs
        var calls = 0

        val first = manager.run("computer", "account", "KV_PUT", "namespace:key", "value-hash", "写入", "run:tool") { calls++ }
        val saved = inserted.captured.copy(status = CloudflareResourceOperationStatus.REQUEST_ACCEPTED.name)
        coEvery { dao.getCloudflareResourceOperation(inserted.captured.operationId) } returns saved

        val second = manager.run("computer", "account", "KV_PUT", "namespace:key", "value-hash", "写入", "run:tool") { calls++ }

        assertEquals(CloudflareResourceOperationStatus.REQUEST_ACCEPTED, first)
        assertEquals(CloudflareResourceOperationStatus.REQUEST_ACCEPTED, second)
        assertEquals(1, calls)
    }

    @Test
    fun `同一可信操作更换参数会拒绝而不执行`() = runTest {
        val dao = mockk<ComputerDao>()
        val manager = CloudflareResourceOperationManager(dao)
        coEvery { dao.getCloudflareResourceOperation(any()) } returns CloudflareResourceOperationEntity(
            operationId = "resource-op-existing",
            computerId = "computer",
            accountId = "account",
            resourceKind = "KV_PUT",
            resourceRef = "namespace:key",
            requestHash = "old-hash",
            status = CloudflareResourceOperationStatus.RESULT_UNKNOWN.name,
            createdAt = 1L,
            updatedAt = 1L,
            safeSummary = "写入",
        )

        assertThrows(CloudflareApiException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.run("computer", "account", "KV_PUT", "namespace:key", "new-hash", "写入", "run:tool") { error("must not run") }
            }
        }
        coVerify(exactly = 0) { dao.insertCloudflareResourceOperationIfAbsent(any()) }
    }

    @Test
    fun `明确 API 拒绝记录为 FAILED`() = runTest {
        val dao = mockk<ComputerDao>()
        val manager = CloudflareResourceOperationManager(dao)
        coEvery { dao.getCloudflareResourceOperation(any()) } returns null
        coEvery { dao.insertCloudflareResourceOperationIfAbsent(any()) } returns 1L
        coEvery { dao.updateCloudflareResourceOperation(any(), any(), any(), any()) } just Runs

        assertThrows(CloudflareApiException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.run("computer", "account", "KV_DELETE", "namespace:key", "delete", "删除", "run:tool") {
                    throw CloudflareApiException("HTTP_403", "拒绝")
                }
            }
        }
        coVerify { dao.updateCloudflareResourceOperation(any(), CloudflareResourceOperationStatus.FAILED.name, any(), any()) }
    }

    @Test
    fun `取消操作记录为 RESULT_UNKNOWN 且不会被当成未发送`() = runTest {
        val dao = mockk<ComputerDao>()
        val manager = CloudflareResourceOperationManager(dao)
        coEvery { dao.getCloudflareResourceOperation(any()) } returns null
        coEvery { dao.insertCloudflareResourceOperationIfAbsent(any()) } returns 1L
        coEvery { dao.updateCloudflareResourceOperation(any(), any(), any(), any()) } just Runs

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.run("computer", "account", "R2_DELETE", "bucket/key", "delete", "删除", "run:tool") {
                    throw CancellationException("取消")
                }
            }
        }
        coVerify { dao.updateCloudflareResourceOperation(any(), CloudflareResourceOperationStatus.RESULT_UNKNOWN.name, any(), any()) }
    }

    private fun slotEntity(): io.mockk.CapturingSlot<CloudflareResourceOperationEntity> = io.mockk.slot()
}
