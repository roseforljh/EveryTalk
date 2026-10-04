package com.android.everytalk.data.computer

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 固定本地 Shell 的信任边界：命令结果不能绕过确认写入持久 Workspace。 */
class LocalBashToolExecutorTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `Shell 文件变化只存在于本次结果不会自动落盘`() = runBlocking {
        val root = folder.newFolder("workspace")
        File(root, "before.txt").writeText("before")
        val runtime = object : JustBashRuntime {
            override suspend fun execute(request: LocalShellRequest): LocalShellResult =
                LocalShellResult(
                    id = request.id,
                    ok = true,
                    exitCode = 0,
                    stdout = "changed token=hidden-value",
                    stderr = "",
                    files = request.files + ("generated.txt" to "generated"),
                )

            override suspend fun reset(workspaceId: String) = Unit
        }
        val executor = LocalBashToolExecutor(runtime, { root })
        val result = executor.execute(
            buildJsonObject { put("command", "echo generated > generated.txt") },
            ComputerRequestContext("conversation", "local", "workspace"),
        )

        assertTrue(result["ok"]?.toString()?.contains("true") == true)
        assertEquals(false, result["persisted"]?.toString()?.toBoolean())
        assertEquals(true, result["workspace_changes_discarded"]?.toString()?.toBoolean())
        assertFalse(result.toString().contains("hidden-value"))
        assertFalse(File(root, "generated.txt").exists())
        assertEquals("before", File(root, "before.txt").readText())
    }

    @Test
    fun `当持久工作区包含非 UTF-8 二进制文件时 local_bash 仍可正常执行且不破坏二进制文件`() = runBlocking {
        val root = folder.newFolder("workspace-with-binary")
        File(root, "sample.txt").writeText("sample text content")
        val binaryBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0xFF.toByte(), 0x00.toByte())
        val binFile = File(root, "image.png")
        binFile.writeBytes(binaryBytes)

        val runtime = object : JustBashRuntime {
            override suspend fun execute(request: LocalShellRequest): LocalShellResult {
                // 快照中只传入合法文本文件，非 UTF-8 二进制文件未污染纯文本请求
                assertTrue(request.files.containsKey("sample.txt"))
                assertFalse(request.files.containsKey("image.png"))
                return LocalShellResult(
                    id = request.id,
                    ok = true,
                    exitCode = 0,
                    stdout = "image.png not in text snapshot",
                    stderr = "",
                    files = request.files,
                )
            }

            override suspend fun reset(workspaceId: String) = Unit
        }
        val executor = LocalBashToolExecutor(runtime, { root })
        val result = executor.execute(
            buildJsonObject { put("command", "ls -l") },
            ComputerRequestContext("conversation", "local", "workspace"),
        )

        // 命令正常执行成功，不再抛出“请检查 Workspace 路径、文件格式和配额”的异常
        assertEquals("true", result["ok"]?.toString())
        assertEquals("0", result["exit_code"]?.toString())
        assertFalse(result.toString().contains("local_bash 执行失败"))

        // 宿主磁盘上的二进制文件完好无损，未被删除或覆盖
        assertTrue(binFile.exists())
        assertTrue(binFile.readBytes().contentEquals(binaryBytes))
    }
}
