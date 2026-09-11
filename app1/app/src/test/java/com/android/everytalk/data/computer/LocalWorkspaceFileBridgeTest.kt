package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkspaceFileBridgeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `只装载 Workspace 内的普通文本文件`() {
        val root = temporaryFolder.newFolder("workspace")
        root.resolve("src.txt").writeText("hello")
        assertEquals("hello", LocalWorkspaceFileBridge().load(root)["src.txt"])
    }

    @Test
    fun `拒绝敏感文件`() {
        val root = temporaryFolder.newFolder("workspace")
        root.resolve("token.pem").writeText("secret")
        assertThrows(IllegalArgumentException::class.java) { LocalWorkspaceFileBridge().load(root) }
    }

    @Test fun `检查深度和嵌套敏感文件不遍历无限目录`() {
        val root = temporaryFolder.newFolder("depth")
        root.resolve((1..21).joinToString("/") { "d$it" }).mkdirs()
        assertThrows(IllegalArgumentException::class.java) { LocalWorkspaceFileBridge().load(root) }
    }

    @Test fun `重建 Bridge 能读取已批准保存的文件`() {
        val root = temporaryFolder.root.resolve("persistent")
        LocalWorkspaceFileBridge().saveText(root, "src/hello.txt", "你好")
        assertEquals("你好", LocalWorkspaceFileBridge().load(root)["src/hello.txt"])
    }

    @Test
    fun `写回支持新文件修改和删除并保持配额边界`() {
        val root = temporaryFolder.newFolder("workspace-writeback")
        root.resolve("old.txt").writeText("old")
        val bridge = LocalWorkspaceFileBridge()
        val before = bridge.load(root)
        bridge.writeBack(root, before, mapOf("new/created.txt" to "new"))
        assertEquals("new", root.resolve("new/created.txt").readText())
        assertEquals(false, root.resolve("old.txt").exists())
    }
}
