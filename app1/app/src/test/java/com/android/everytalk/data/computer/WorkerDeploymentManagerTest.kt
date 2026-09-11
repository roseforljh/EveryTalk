package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareDeploymentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证 Worker 上传成功后必须经过 deployment 查询，且查询失败只能留下 UNKNOWN。 */
class WorkerDeploymentManagerTest {
    @Test
    fun `上传后查询成功才写入部署成功`() = runTest {
        val dao = mockk<ComputerDao>()
        val api = mockk<CloudflareApiClient>()
        val saved = mutableListOf<CloudflareDeploymentEntity>()
        coEvery { dao.getCloudflareDeploymentByHash(any()) } returns null
        coEvery { dao.insertCloudflareDeploymentIfAbsent(any()) } returns 1L
        coEvery { dao.upsertCloudflareDeployment(capture(saved)) } just Runs
        coEvery { api.deployModuleWorker(any(), any(), any<WorkerPackage>(), any()) } returns
            CloudflareDeploymentResult("demo", "deployment-1", CloudflareDeploymentStatus.REQUEST_ACCEPTED, "version-1")
        coEvery { api.workerDeploymentStatus("account", "demo", "deployment-1") } returns
            CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED

        val result = WorkerDeploymentManager(dao, api).deployPackage("computer", "account", "demo", packageData())

        assertEquals(CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED, result.status)
        assertEquals(CloudflareDeploymentStatus.REQUEST_ACCEPTED.name, saved.first().status)
        assertEquals(CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED.name, saved.last().status)
        assertEquals("deployment-1", saved.last().remoteDeploymentId)
    }

    @Test
    fun `上传已接受但状态查询失败时保留 UNKNOWN 且不重复上传`() = runTest {
        val dao = mockk<ComputerDao>()
        val api = mockk<CloudflareApiClient>()
        val saved = mutableListOf<CloudflareDeploymentEntity>()
        coEvery { dao.getCloudflareDeploymentByHash(any()) } returns null
        coEvery { dao.insertCloudflareDeploymentIfAbsent(any()) } returns 1L
        coEvery { dao.upsertCloudflareDeployment(capture(saved)) } just Runs
        coEvery { api.deployModuleWorker(any(), any(), any<WorkerPackage>(), any()) } returns
            CloudflareDeploymentResult("demo", "deployment-1", CloudflareDeploymentStatus.REQUEST_ACCEPTED, "version-1")
        coEvery { api.workerDeploymentStatus(any(), any(), any()) } throws CloudflareApiException("NETWORK_ERROR", "网络中断")

        val result = WorkerDeploymentManager(dao, api).deployPackage("computer", "account", "demo", packageData())

        assertEquals(CloudflareDeploymentStatus.RESULT_UNKNOWN, result.status)
        assertEquals("deployment-1", saved.last().remoteDeploymentId)
        coVerify(exactly = 1) { api.deployModuleWorker(any(), any(), any<WorkerPackage>(), any()) }
    }

    @Test fun `版本上传后取消仍保留版本且抛出取消信号`() = runTest {
        val dao = mockk<ComputerDao>()
        val api = mockk<CloudflareApiClient>()
        val saved = mutableListOf<CloudflareDeploymentEntity>()
        coEvery { dao.getCloudflareDeploymentByHash(any()) } returns null
        coEvery { dao.insertCloudflareDeploymentIfAbsent(any()) } returns 1L
        coEvery { dao.upsertCloudflareDeployment(capture(saved)) } just Runs
        coEvery { api.deployModuleWorker(any(), any(), any<WorkerPackage>(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3)("version-known")
            throw kotlinx.coroutines.CancellationException("中断")
        }
        try {
            WorkerDeploymentManager(dao, api).deployPackage("computer", "account", "demo", packageData())
            org.junit.Assert.fail("取消必须传播")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("version-known", saved.first().versionId)
        assertEquals("version-known", saved.last().versionId)
        assertEquals(CloudflareDeploymentStatus.RESULT_UNKNOWN.name, saved.last().status)
        coVerify(exactly = 0) { api.workerDeploymentStatus(any(), any(), any()) }
    }

    @Test fun `查询期间取消不会覆盖已经保存的部署 ID`() = runTest {
        val dao = mockk<ComputerDao>()
        val api = mockk<CloudflareApiClient>()
        val saved = mutableListOf<CloudflareDeploymentEntity>()
        coEvery { dao.getCloudflareDeploymentByHash(any()) } returns null
        coEvery { dao.insertCloudflareDeploymentIfAbsent(any()) } returns 1L
        coEvery { dao.upsertCloudflareDeployment(capture(saved)) } just Runs
        coEvery { api.deployModuleWorker(any(), any(), any<WorkerPackage>(), any()) } returns
            CloudflareDeploymentResult("demo", "deployment-known", CloudflareDeploymentStatus.REQUEST_ACCEPTED, "version-known")
        coEvery { api.workerDeploymentStatus(any(), any(), any()) } coAnswers {
            assertEquals("deployment-known", saved.last().remoteDeploymentId)
            throw kotlinx.coroutines.CancellationException("中断")
        }
        try {
            WorkerDeploymentManager(dao, api).deployPackage("computer", "account", "demo", packageData())
            org.junit.Assert.fail("取消必须传播")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("deployment-known", saved.last().remoteDeploymentId)
        assertEquals(CloudflareDeploymentStatus.RESULT_UNKNOWN.name, saved.last().status)
    }

    private fun packageData() = WorkerPackage(
        files = listOf(WorkerPackageFile("worker.js", "export default {}".toByteArray(), "hash")),
        requestHash = "request-hash",
        totalBytes = 18,
    )
}
