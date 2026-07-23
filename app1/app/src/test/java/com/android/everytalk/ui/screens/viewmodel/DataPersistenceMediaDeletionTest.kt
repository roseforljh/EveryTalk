package com.android.everytalk.ui.screens.viewmodel

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DataPersistenceMediaDeletionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `本地媒体来源兼容 Android 与 Windows 绝对路径`() {
        val androidPath = "/data/user/0/com.android.everytalk/files/chat_attachments/a b.png"
        val windowsPath = "C:\\Users\\tester\\EveryTalk\\chat_attachments\\a.png"

        assertEquals(androidPath, mediaSourceToLocalPath(androidPath))
        assertEquals(
            androidPath,
            mediaSourceToLocalPath("file:///data/user/0/com.android.everytalk/files/chat_attachments/a%20b.png"),
        )
        assertEquals(windowsPath, mediaSourceToLocalPath(windowsPath))
        assertEquals(
            if (File.separatorChar == '\\') "C:/Users/tester/a.png" else "/C:/Users/tester/a.png",
            mediaSourceToLocalPath("file:///C:/Users/tester/a.png"),
        )
        assertNull(mediaSourceToLocalPath("content://provider/image/1"))
        assertNull(mediaSourceToLocalPath("https://example.com/image.png"))
        assertNull(mediaSourceToLocalPath("relative/image.png"))
        assertNull(mediaSourceToLocalPath("file://server/share/image.png"))
    }

    @Test
    fun `仅解析应用明确媒体目录中的真实普通文件`() {
        val layout = createLayout()

        layout.allowedDirectories.forEachIndexed { index, directory ->
            val file = File(directory, "media $index.bin").apply { writeText("owned") }
            val expected = file.toPath().toRealPath().toFile()

            assertEquals(expected, resolveOwnedMediaFile(file.absolutePath, layout.allowedDirectories))
            assertEquals(expected, resolveOwnedMediaFile(file.toURI().toString(), layout.allowedDirectories))
        }

        assertNull(resolveOwnedMediaFile(layout.allowedDirectories.first().absolutePath, layout.allowedDirectories))
    }

    @Test
    fun `路径穿越和相似目录前缀不能越过受控目录`() {
        val layout = createLayout()
        val attachmentDirectory = layout.allowedDirectories.first()
        val outsideFile = File(layout.filesDirectory, "outside.txt").apply { writeText("outside") }
        val prefixDirectory = File(layout.filesDirectory, "chat_attachments_backup").apply { mkdirs() }
        val prefixFile = File(prefixDirectory, "prefix.txt").apply { writeText("prefix") }
        val traversalSource = File(attachmentDirectory, "../${outsideFile.name}").path

        assertNull(resolveOwnedMediaFile(outsideFile.absolutePath, layout.allowedDirectories))
        assertNull(resolveOwnedMediaFile(prefixFile.absolutePath, layout.allowedDirectories))
        assertNull(resolveOwnedMediaFile(traversalSource, layout.allowedDirectories))
        assertTrue(outsideFile.exists())
        assertTrue(prefixFile.exists())
    }

    @Test
    fun `受控目录内符号链接不能指向目录外文件`() {
        val layout = createLayout()
        val attachmentDirectory = layout.allowedDirectories.first()
        val outsideFile = File(layout.filesDirectory, "outside-link-target.txt").apply { writeText("outside") }
        val link = File(attachmentDirectory, "linked.txt").toPath()

        try {
            Files.createSymbolicLink(link, outsideFile.toPath())
        } catch (exception: Exception) {
            assumeNoException("当前文件系统不支持创建符号链接", exception)
        }

        assertNull(resolveOwnedMediaFile(link.toString(), layout.allowedDirectories))
        assertTrue(outsideFile.exists())
    }

    @Test
    fun `删除候选只来自结构化媒体字段且忽略正文恶意路径`() {
        val layout = createLayout()
        val ownedFile = File(layout.allowedDirectories.first(), "owned.png").apply { writeText("owned") }
        val outsideFile = File(layout.filesDirectory, "do-not-delete.txt").apply { writeText("outside") }
        val remoteUrl = "https://example.com/image.png"
        val message = Message(
            text = "恶意正文 file://${outsideFile.absolutePath} ${outsideFile.absolutePath}",
            sender = Sender.User,
            imageUrls = listOf(
                ownedFile.toURI().toString(),
                remoteUrl,
                "data:image/png;base64,${"A".repeat(10_000)}",
            ),
            attachments = listOf(
                SelectedMediaItem.ImageFromBitmap(
                    bitmapData = "",
                    id = "owned",
                    filePath = ownedFile.absolutePath,
                ),
                SelectedMediaItem.Audio(
                    id = "audio",
                    mimeType = "audio/wav",
                    data = "A".repeat(10_000),
                ),
            ),
        )

        val candidates = collectMediaDeletionCandidates(listOf(message))

        assertTrue(candidates.localSources.contains(ownedFile.absolutePath))
        assertTrue(candidates.localSources.contains(ownedFile.toURI().toString()))
        assertFalse(candidates.localSources.contains(outsideFile.absolutePath))
        assertEquals(2, candidates.localSources.size)
        assertEquals(setOf(remoteUrl), candidates.remoteUrls)
    }

    @Test
    fun `孤立附件引用统一解析 file URI 并忽略内联音频`() {
        val layout = createLayout()
        val ownedFile = File(layout.allowedDirectories.first(), "owned space.png").apply { writeText("owned") }
        val message = Message(
            text = "",
            sender = Sender.User,
            imageUrls = listOf(ownedFile.toURI().toString()),
            attachments = listOf(
                SelectedMediaItem.Audio(
                    id = "audio",
                    mimeType = "audio/wav",
                    data = "A".repeat(10_000),
                ),
            ),
        )

        assertEquals(setOf(ownedFile.canonicalPath), collectReferencedAttachmentPaths(listOf(message)))
    }

    private fun createLayout(): TestLayout {
        val filesDirectory = temporaryFolder.newFolder("files")
        val cacheDirectory = temporaryFolder.newFolder("cache")
        val allowedDirectories = listOf(
            File(filesDirectory, "chat_attachments"),
            File(filesDirectory, "chat_images"),
            File(filesDirectory, "chat_images_temp"),
            File(cacheDirectory, "preview_cache"),
            File(cacheDirectory, "share_images"),
        ).onEach { it.mkdirs() }
        return TestLayout(filesDirectory, allowedDirectories)
    }

    private data class TestLayout(
        val filesDirectory: File,
        val allowedDirectories: List<File>,
    )
}
