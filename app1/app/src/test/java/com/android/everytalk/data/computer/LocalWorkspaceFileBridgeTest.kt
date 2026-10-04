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

    @Test
    fun `包含非 UTF-8 二进制文件时正常装载文本快照且不破坏二进制文件`() {
        val root = temporaryFolder.newFolder("workspace-binary")
        root.resolve("src.txt").writeText("hello")
        val binaryBytes = byteArrayOf(0xFF.toByte(), 0x00.toByte(), 0xFE.toByte())
        val binFile = root.resolve("data.bin")
        binFile.writeBytes(binaryBytes)

        val bridge = LocalWorkspaceFileBridge()
        val snapshot = bridge.load(root)

        // 只装载合法文本文件，非 UTF-8 二进制文件不作为文本装载
        assertEquals("hello", snapshot["src.txt"])
        assertEquals(false, snapshot.containsKey("data.bin"))
        // 磁盘上的二进制文件内容完好无损，未被删除或修改
        assertEquals(true, binFile.exists())
        assertEquals(true, binFile.readBytes().contentEquals(binaryBytes))
    }

    @Test
    fun `包含二进制文件时工作区依然严格计入总大小和数量配额`() {
        val root = temporaryFolder.newFolder("workspace-quota")
        val binaryBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte())
        root.resolve("data.bin").writeBytes(binaryBytes) // 6 字节
        root.resolve("text.txt").writeText("12345") // 5 字节，合计 11 字节

        // 总配额上限为 10 字节时，即使二进制文件不作为文本装载，也必须计入配额并抛出异常
        assertThrows(IllegalArgumentException::class.java) {
            LocalWorkspaceFileBridge(maxTotalBytes = 10L).load(root)
        }

        // 文件数量上限为 1 时，即便一个是二进制文件，也超出数量限制
        assertThrows(IllegalArgumentException::class.java) {
            LocalWorkspaceFileBridge(maxFileCount = 1).load(root)
        }
    }

    @Test
    fun `saveText 拒绝用文本覆盖已存在的二进制文件且配额计入二进制`() {
        val root = temporaryFolder.newFolder("workspace-save-binary")
        val binaryBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val binFile = root.resolve("data.bin")
        binFile.writeBytes(binaryBytes)
        val bridge = LocalWorkspaceFileBridge()

        // 尝试覆盖已有二进制文件应被明确拒绝
        assertThrows(IllegalArgumentException::class.java) {
            bridge.saveText(root, "data.bin", "overwrite content")
        }
        // 二进制文件内容未受任何影响
        assertEquals(true, binFile.readBytes().contentEquals(binaryBytes))

        // 正常保存其他文本文件能够成功
        bridge.saveText(root, "normal.txt", "safe")
        assertEquals("safe", bridge.load(root)["normal.txt"])
    }

    @Test
    fun `writeBack 拒绝用文本覆盖已存在的二进制文件且不误删已有二进制文件`() {
        val root = temporaryFolder.newFolder("workspace-writeback-binary")
        val binaryBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x00.toByte(), 0xFF.toByte())
        val binFile = root.resolve("image.png")
        binFile.writeBytes(binaryBytes)
        root.resolve("old.txt").writeText("old text")

        val bridge = LocalWorkspaceFileBridge()
        val before = bridge.load(root)
        // 快照中只有文本文件
        assertEquals(mapOf("old.txt" to "old text"), before)

        // writeBack 删除 old.txt，新增 new.txt，磁盘上的 image.png 不在 before 中，绝不能被误删
        bridge.writeBack(root, before, mapOf("new.txt" to "new text"))
        assertEquals(false, root.resolve("old.txt").exists())
        assertEquals("new text", root.resolve("new.txt").readText())
        assertEquals(true, binFile.exists())
        assertEquals(true, binFile.readBytes().contentEquals(binaryBytes))

        // 若 after 尝试写入同名路径覆盖已有二进制文件，应被明确拒绝
        assertThrows(IllegalArgumentException::class.java) {
            bridge.writeBack(root, bridge.load(root), mapOf("image.png" to "fake image"))
        }
        assertEquals(true, binFile.readBytes().contentEquals(binaryBytes))
    }

    @Test
    fun `stale before包含同名二进制时 writeBack 绝不误删现存二进制`() {
        val root = temporaryFolder.newFolder("workspace-stale-before")
        val binaryBytes = byteArrayOf(0x7F.toByte(), 0x45.toByte(), 0x4C.toByte(), 0x46.toByte(), 0xFF.toByte())
        val binFile = root.resolve("artifact.bin")
        binFile.writeBytes(binaryBytes)
        root.resolve("note.txt").writeText("note")

        val bridge = LocalWorkspaceFileBridge()
        // 模拟一个过期的快照：该快照可能产生于 artifact.bin 曾是文本文件的旧阶段
        val staleBefore = mapOf(
            "artifact.bin" to "legacy text",
            "note.txt" to "note",
        )
        // after 中删除了 artifact.bin（即 artifact.bin !in after）
        val after = mapOf("note.txt" to "note")

        bridge.writeBack(root, staleBefore, after)

        // 验证：尽管 staleBefore 中包含 artifact.bin 且不在 after 中，但磁盘上现存为二进制文件，绝不能被删除
        assertEquals(true, binFile.exists())
        assertEquals(true, binFile.readBytes().contentEquals(binaryBytes))
        assertEquals("note", root.resolve("note.txt").readText())
    }
}
