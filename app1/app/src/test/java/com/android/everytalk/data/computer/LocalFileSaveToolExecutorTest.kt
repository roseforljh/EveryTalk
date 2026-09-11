package com.android.everytalk.data.computer

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileSaveToolExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `未批准不能保存且批准后生成文件`() = runBlocking {
        val root = folder.newFolder("workspace")
        val executor = LocalFileSaveToolExecutor({ root })
        val base = ComputerRequestContext("conversation", "local", "workspace", runId = null)
        val args = buildJsonObject { put("path", "demo/index.html"); put("content", "<h1>ok</h1>") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { executor.execute(args, "call-1", base) } }
        val approval = executor.approval(args, "call-1", base)
        val approvedContext = base.copy(
            runId = "run-1",
            approvedToolCallId = "call-1",
            approvedLocalFileWrite = approval.copy(context = approval.context.copy(runId = "run-1")),
        )
        val result = executor.execute(args, "call-1", approvedContext)
        assertEquals("demo/index.html", result.path)
        assertEquals("<h1>ok</h1>", File(root, result.path).readText())
    }

    @Test
    fun `审批后内容改变会拒绝保存`() = runBlocking {
        val root = folder.newFolder("workspace")
        val executor = LocalFileSaveToolExecutor({ root })
        val base = ComputerRequestContext("conversation", "local", "workspace")
        val first = buildJsonObject { put("path", "a.txt"); put("content", "one") }
        val approval = executor.approval(first, "call-2", base)
        val changed = buildJsonObject { put("path", "a.txt"); put("content", "two") }
        val context = base.copy(
            runId = "run",
            approvedToolCallId = "call-2",
            approvedLocalFileWrite = approval.copy(context = approval.context.copy(runId = "run")),
        )
        assertThrows(IllegalArgumentException::class.java) { runBlocking { executor.execute(changed, "call-2", context) } }
        Unit
    }
}
