package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerAddCancellationTest {
    @Test fun `记录写入后取消保留凭据并恢复离线`() = checkCancellation(savedBeforeCancel = true)
    @Test fun `记录写入前取消清理临时凭据`() = checkCancellation(savedBeforeCancel = false)

    private fun checkCancellation(savedBeforeCancel: Boolean) = runBlocking {
        val dao = mockk<ComputerDao>(relaxed = true)
        val credentials = mockk<ComputerCredentialStore>(relaxed = true)
        val pool = mockk<ComputerConnectionPool>(relaxed = true)
        // 只执行真实的添加方法，隔离 Android 数据库、Keystore 和进程级连接池初始化。
        val repository = mockk<ComputerRepository>()
        mapOf("dao" to dao, "credentialStore" to credentials, "connectionPool" to pool,
            "addComputerMutex" to Mutex(), "json" to Json).forEach { (name, value) ->
            ComputerRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repository, value)
        }
        coEvery { repository.addConfirmedComputer(any(), any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { dao.findActiveSshComputer(any(), any(), any()) } returns null
        var saved: ComputerEntity? = null
        val reachedWrite = CompletableDeferred<Unit>()
        coEvery { dao.upsertComputer(any()) } coAnswers {
            if (savedBeforeCancel) saved = firstArg()
            reachedWrite.complete(Unit)
            awaitCancellation()
        }
        coEvery { dao.getComputer("cancel-add") } answers { saved }
        val password = "secret".toCharArray()
        val request = AddComputerRequest(id = "cancel-add", displayName = "test", host = "example.com",
            port = 22, username = "root", credential = ComputerCredential.Password(password),
            runMode = ComputerRunMode.DIRECT)
        val key = HostKeyProbeResult("example.com", "192.0.2.1", 22, "ssh-ed25519", byteArrayOf(1), "SHA256:test")
        val job = launch { repository.addConfirmedComputer(request, key, null) }
        withTimeout(5_000) { reachedWrite.await(); job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(password.all { it == '\u0000' })
        if (savedBeforeCancel) {
            coVerify { dao.updateComputerStatus("cancel-add", ComputerStatus.OFFLINE.name, null, any()) }
            coVerify(exactly = 0) { credentials.deleteComputerCredential(any()) }
        } else {
            coVerify { credentials.deleteComputerCredential("cancel-add") }
            coVerify { credentials.deleteOriginalComputerCredential("cancel-add") }
            coVerify { credentials.deleteComputerSudoPassword("cancel-add") }
        }
    }
}
