package com.android.everytalk.data.computer

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 覆盖默认上传真正发布文件的步骤；原有附件桥接测试没有执行到 SFTP rename。 */
class ComputerFileTransferTest {
    private val root = "/home/test/.everytalk/workspaces/transfer-test"
    private fun workspace() = ComputerWorkspace("transfer-test", "test", "test", ComputerRunMode.DIRECT, root)

    @Test
    fun `SFTPv3默认上传不能携带会被SSHJ拒绝的原子重命名参数`() = runBlocking {
        val files = linkedMapOf<String, ByteArray>()
        val sftp = mockk<SFTPClient>(relaxed = true)
        val client = mockk<SSHClient>(relaxed = true)
        every { client.isConnected } returns true
        every { client.isAuthenticated } returns true
        every { client.newSFTPClient() } returns sftp
        every { sftp.canonicalize(root) } returns root
        every { sftp.canonicalize(".") } returns "/home/test"
        every { sftp.lstat(any()) } answers {
            val path = firstArg<String>()
            if (path != root && path !in files) throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "missing")
            mockk<FileAttributes> { every { type } returns if (path == root) FileMode.Type.DIRECTORY else FileMode.Type.REGULAR }
        }
        every { sftp.open(any(), any()) } answers {
            val path = firstArg<String>()
            files[path] = byteArrayOf()
            mockk<RemoteFile>(relaxed = true).apply {
                every { write(any(), any(), any(), any()) } answers {
                    val position = firstArg<Long>().toInt()
                    val data = secondArg<ByteArray>()
                    val offset = thirdArg<Int>()
                    val count = arg<Int>(3)
                    files[path] = files.getValue(path).copyOf(position + count).also { data.copyInto(it, position, offset, offset + count) }
                }
            }
        }
        every { sftp.size(any()) } answers { files.getValue(firstArg()).size.toLong() }
        every { sftp.rm(any()) } answers { files.remove(firstArg()); Unit }
        every { sftp.rename(any(), any(), any()) } answers {
            val source = firstArg<String>(); val target = secondArg<String>(); val flags = thirdArg<Set<RenameFlags>>()
            // SSHJ 0.40 在 v3 + OpenSSH 扩展环境下的真实限制：ATOMIC 必须同时允许 OVERWRITE。
            if (RenameFlags.ATOMIC in flags && RenameFlags.OVERWRITE !in flags) throw SFTPException("RENAME-FLAGS are not supported in SFTPv3")
            if (target in files && RenameFlags.OVERWRITE !in flags) throw SFTPException(Response.StatusCode.FAILURE, "exists")
            files[target] = files.remove(source)!!
        }
        ComputerSshConnection(client).use { connection ->
            val transfer = ComputerFileTransfer()
            val bytes = ByteArray(150_000) { (it % 251).toByte() }
            transfer.upload(connection, workspace(), "upload-1", "archive.zip", ByteArrayInputStream(bytes), bytes.size.toLong(), false)
            assertArrayEquals(bytes, files["$root/archive.zip"])
            val duplicate = runCatching { transfer.upload(connection, workspace(), "upload-2", "archive.zip", ByteArrayInputStream(byteArrayOf(7)), 1, false) }.exceptionOrNull()
            assertEquals(ComputerErrorCodes.WORKSPACE_PATH_INVALID, (duplicate as ComputerException).code)
            assertArrayEquals(bytes, files["$root/archive.zip"])
            transfer.upload(connection, workspace(), "upload-3", "archive.zip", ByteArrayInputStream(byteArrayOf(7)), 1, true)
            assertArrayEquals(byteArrayOf(7), files["$root/archive.zip"])
            val mismatch = runCatching { transfer.upload(connection, workspace(), "upload-4", "bad.zip", ByteArrayInputStream(bytes), bytes.size + 1L, false) }.exceptionOrNull()
            assertEquals(ComputerErrorCodes.UPLOAD_INTERRUPTED, (mismatch as ComputerException).code)
            assertEquals(setOf("$root/archive.zip"), files.keys)
        }
    }

    /**
     * 可选的真实协议检查：EVERYTALK_SFTP_TEST_PORT 指向本机 SFTP v3 测试服务。
     * 仅连接 127.0.0.1，使用测试账号 test/test 和隔离 Workspace，不读取用户服务器凭据。
     */
    @Test
    fun `真实SFTPv3压缩包上传下载字节一致且不覆盖同名文件`() = runBlocking {
        val port = System.getenv("EVERYTALK_SFTP_TEST_PORT")?.toIntOrNull()
        assumeNotNull(port)
        SSHClient().use { client ->
            client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean = hostname == "127.0.0.1"
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connect("127.0.0.1", requireNotNull(port))
            client.authPassword("test", "test")
            ComputerSshConnection(client).use { connection ->
                connection.withSftp { sftp ->
                    assertEquals(3, sftp.sftpEngine.operativeProtocolVersion)
                    // 先用旧参数复现实际异常，再验证生产 upload 的修复；此调用在发包前就被 SSHJ 拒绝。
                    val oldFailure = runCatching {
                        sftp.rename("$root/unused-temp", "$root/unused-final", java.util.EnumSet.of(RenameFlags.ATOMIC))
                    }.exceptionOrNull()
                    assertTrue(oldFailure is SFTPException)
                    assertTrue(oldFailure?.message.orEmpty().contains("RENAME-FLAGS are not supported in SFTPv3"))
                }
                val payload = ByteArray(180_000).also { java.util.Random(42).nextBytes(it) }
                val zipped = ByteArrayOutputStream().also { output ->
                    ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("diagnostics.log")); zip.write(payload); zip.closeEntry() }
                }.toByteArray()
                val transfer = ComputerFileTransfer()
                val name = "diagnostics-${UUID.randomUUID()}.zip"
                val badName = "bad-${UUID.randomUUID()}.zip"
                val uploaded = transfer.upload(connection, workspace(), "real-upload", name, ByteArrayInputStream(zipped), zipped.size.toLong(), false)
                val output = ByteArrayOutputStream()
                val downloaded = transfer.download(connection, workspace(), name, output)
                assertEquals(uploaded.sha256, downloaded.sha256)
                assertArrayEquals(zipped, output.toByteArray())
                ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                    assertEquals("diagnostics.log", zip.nextEntry.name)
                    assertArrayEquals(payload, zip.readBytes())
                }
                assertTrue(runCatching { transfer.upload(connection, workspace(), "real-duplicate", name, ByteArrayInputStream(byteArrayOf(9)), 1, false) }.exceptionOrNull() is ComputerException)
                val unchanged = ByteArrayOutputStream()
                transfer.download(connection, workspace(), name, unchanged)
                assertArrayEquals(zipped, unchanged.toByteArray())
                transfer.upload(connection, workspace(), "real-overwrite", name, ByteArrayInputStream(byteArrayOf(9)), 1, true)
                val changed = ByteArrayOutputStream()
                transfer.download(connection, workspace(), name, changed)
                assertArrayEquals(byteArrayOf(9), changed.toByteArray())
                assertTrue(runCatching { transfer.upload(connection, workspace(), "real-bad", badName, ByteArrayInputStream(zipped), zipped.size + 1L, false) }.exceptionOrNull() is ComputerException)
                connection.withSftp { sftp ->
                    assertNull(sftp.statExistence("$root/$badName"))
                    assertNull(sftp.statExistence("$root/$badName.everytalk-real-bad.upload"))
                    sftp.rm("$root/$name")
                }
            }
        }
    }
}
