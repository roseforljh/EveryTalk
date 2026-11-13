package com.android.everytalk.data.computer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CloudAgentInputStorageTest {
    @Test fun `分块冻结输入仅存密文跨实例恢复且不允许改写或跨账号搬用`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val account = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val run = UUID.randomUUID().toString()
        val source = ByteArray(5 * 1024 * 1024 + 7) { (it % 113).toByte() }
        "test-sensitive-input".toByteArray().copyInto(source)
        fun file(user: String, index: String = ""): File {
            val scope = "cloud-agent:$user:$run$index"
            val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(context.noBackupFilesDir, "computer_credentials/$name.bin")
        }
        val first = ComputerCredentialStore(context, key)
        try {
            first.saveCloudAgentInput(account, run, source)
            val reopened = ComputerCredentialStore(context, key)
            assertArrayEquals(source, reopened.loadCloudAgentInput(account, run))
            assertNull(reopened.loadCloudAgentInput(other, run))
            assertFalse(file(account, ":0").readBytes().toString(Charsets.ISO_8859_1).contains("test-sensitive-input"))
            reopened.saveCloudAgentInput(account, run, source)
            assertTrue(runCatching { reopened.saveCloudAgentInput(account, run, "changed".toByteArray()) }.isFailure)
            assertArrayEquals(source, reopened.loadCloudAgentInput(account, run))
            file(account).copyTo(file(other), overwrite = true)
            assertTrue(runCatching { reopened.loadCloudAgentInput(other, run) }.isFailure)
            reopened.deleteCloudAgentInput(account, run)
            assertNull(first.loadCloudAgentInput(account, run))
            assertTrue((0..23).none { file(account, ":$it").exists() })
        } finally {
            first.deleteCloudAgentInput(account, run); first.deleteCloudAgentInput(other, run); source.fill(0)
        }
    }
}
