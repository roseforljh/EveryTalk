package com.android.everytalk.data.computer

import com.android.everytalk.data.DataClass.ExecutionFileChange
import com.android.everytalk.data.DataClass.ExecutionFileEdit
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.fileChangeSummaries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitWorkingTreeTest {
    private val patch = listOf(
        "diff --git a/src/a.kt b/src/a.kt", "index c0d0fb4..1ce6a47 100644", "--- a/src/a.kt", "+++ b/src/a.kt",
        "@@ -2 +2,2 @@ line1", "-line2", "+line2 changed", "+edit by sed",
        "diff --git a/src/b.kt b/src/b.kt", "deleted file mode 100644", "index 2fa992c..0000000", "--- a/src/b.kt", "+++ /dev/null",
        "@@ -1 +0,0 @@", "-keep",
        "diff --git a/src/c.kt b/src/c.kt", "new file mode 100644", "index 0000000..39963cd", "--- /dev/null", "+++ b/src/c.kt",
        "@@ -0,0 +1,2 @@", "+brand new", "+file",
        "T 94ca347c866d2e94724e25880809b3d587f5d694",
    ).joinToString("\n")

    @Test
    fun `任意命令的改动按工作树补丁显示 修改删除新增都在`() {
        val changes = GitWorkingTree.parse(patch)
        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt"), changes.map { it.path })
        assertEquals(listOf(ExecutionFileEdit("line2", "line2 changed\nedit by sed")), changes[0].edits)
        assertEquals(listOf(ExecutionFileEdit("keep", "")), changes[1].edits)
        assertEquals(listOf(ExecutionFileEdit("", "brand new\nfile")), changes[2].edits)
        // 没有改动的输出和纯文本输出都不能伪造成改动。
        assertEquals(emptyList<ExecutionFileChange>(), GitWorkingTree.parse("T 94ca347c866d2e94724e25880809b3d587f5d694"))
        assertEquals(emptyList<ExecutionFileChange>(), GitWorkingTree.parse(""))
    }

    @Test
    fun `输出上限内的启动脚本原样发送且cwd不可注入`() {
        val command = GitWorkingTree.command("~/a b'c", "94ca347c866d2e94724e25880809b3d587f5d694")
        assertTrue(command.startsWith("sh -c '"))
        assertTrue(command.contains("diff-tree"))
        assertTrue(command.endsWith(" '~/a b'\\''c' after '94ca347c866d2e94724e25880809b3d587f5d694'"))
        assertEquals("", GitWorkingTree.tree("noise\nT 94ca347c866d2e94724e25880809b3d587f5d694\n"))
        assertTrue(runCatching { GitWorkingTree.command("bad\ncwd") }.isFailure)
    }

    @Test
    fun `云端结果和本机结果用同一份结构 胶囊合并多文件`() {
        val json = Json.parseToJsonElement(GitWorkingTree.toJson(GitWorkingTree.parse(patch)).toString())
        assertEquals(GitWorkingTree.parse(patch), GitWorkingTree.fromResult(json))
        assertEquals(GitWorkingTree.parse(patch),
            GitWorkingTree.fromResult(buildJsonObject { put("data", json) }))
        val wrapped = buildJsonObject { put("content", Json.parseToJsonElement("""[{"type":"text","text":"{\"file_changes\":${json}}"}]""")) }
        assertEquals(GitWorkingTree.parse(patch), GitWorkingTree.fromResult(wrapped))
        val steps = listOf(ExecutionStep("exec-1", ExecutionStepType.Agent, "exec", toolName = "exec", fileChanges = GitWorkingTree.parse(patch)))
        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt"), fileChangeSummaries(steps).map { it.path })
        assertEquals(3, fileChangeSummaries(steps).sumOf { it.additions + it.deletions })
    }
}
