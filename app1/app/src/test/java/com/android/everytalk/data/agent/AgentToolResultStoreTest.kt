package com.android.everytalk.data.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentToolResultStoreTest {
    @Test
    fun `归档不覆盖且分页Unicode完整并拒绝跨Run和损坏`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AgentToolResultStore(context)
        val first = requireNotNull(store.archive("read-run", "same", JsonPrimitive("a😀b")))
        val second = requireNotNull(store.archive("read-run", "same", JsonPrimitive("new")))
        assertFalse(first.relativePath == second.relativePath)
        val result = AgentContentBlock.ToolResult("same", "exec", JsonPrimitive("short"),
            fullResultPath = first.relativePath, fullResultBytes = first.byteCount, fullResultSha256 = first.sha256)
        val text = store.read("read-run", result)
        var offset = 0
        val joined = StringBuilder()
        while (offset < text.length) {
            val page = AgentToolResultReadTool.page(text, kotlinx.serialization.json.buildJsonObject {
                put("offset", kotlinx.serialization.json.JsonPrimitive(offset)); put("limit", kotlinx.serialization.json.JsonPrimitive(3))
            }, 64)
            joined.append((page.getValue("text") as JsonPrimitive).content)
            offset = (page.getValue("next_offset") as JsonPrimitive).content.toInt()
        }
        assertEquals(text, joined.toString())
        assertTrue(runCatching { store.read("other-run", result) }.isFailure)
        File(context.filesDir, first.relativePath).writeText("corrupt")
        assertTrue(runCatching { store.read("read-run", result) }.isFailure)
        store.deleteRun("read-run")
        assertTrue(runCatching { store.read("read-run", result) }.isFailure)
        assertTrue(runCatching { AgentToolResultReadTool.page(text, kotlinx.serialization.json.buildJsonObject {
            put("offset", JsonPrimitive(-1))
        }, 64) }.isFailure)
        assertTrue(CodemodeTool.callableTools(listOf(AgentToolResultReadTool.definition)).isEmpty())
    }

    @Test
    fun `完整结果写入私有目录并可按Run清理`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AgentToolResultStore(context)

        val archive = store.archive("run-archive", "tool-archive", JsonPrimitive("完整结果"))

        assertNotNull(archive)
        val file = File(context.filesDir, requireNotNull(archive).relativePath)
        assertTrue(file.isFile)
        assertEquals(JsonPrimitive("完整结果").toString(), file.readText())
        assertTrue(archive.byteCount > 0L)
        assertEquals(64, archive.sha256.length)
        assertFalse(file.resolveSibling("${file.name}.tmp").exists())

        store.deleteRun("run-archive")
        assertFalse(file.exists())
    }
}
