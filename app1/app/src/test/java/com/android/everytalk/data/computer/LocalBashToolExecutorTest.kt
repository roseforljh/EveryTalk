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
}
