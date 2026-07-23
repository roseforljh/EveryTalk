package com.android.everytalk.ui.screens.viewmodel

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.RoomDataSource
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ViewModelStateHolder
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class DataPersistenceOrphanAttachmentTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        stopKoin()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns database
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkObject(AppDatabase.Companion)
        database.close()
        stopKoin()
    }

    @Test
    fun `孤儿清理保留四类会话中的图片和附件引用`() = runBlocking {
        val attachmentsDir = File(context.filesDir, "chat_attachments").apply { mkdirs() }
        val textUrl = createFile(attachmentsDir, "text-url.png")
        val textAttachment = createFile(attachmentsDir, "text-file.pdf")
        val imageUrl = createFile(attachmentsDir, "image-url.png")
        val imageAttachment = createFile(attachmentsDir, "image-file.pdf")
        val lastTextUrl = createFile(attachmentsDir, "last-text-url.png")
        val lastTextAttachment = createFile(attachmentsDir, "last-text-file.pdf")
        val lastImageUrl = createFile(attachmentsDir, "last-image-url.png")
        val lastImageAttachment = createFile(attachmentsDir, "last-image-file.pdf")
        val orphan = createFile(attachmentsDir, "orphan.bin")

        val dataSource = RoomDataSource(context)
        dataSource.saveChatHistory(
            listOf(conversation("text-history", textUrl, textAttachment))
        )
        dataSource.saveImageGenerationHistory(
            listOf(conversation("image-history", imageUrl, imageAttachment))
        )
        dataSource.saveLastOpenChat(
            conversation("last-text", lastTextUrl, lastTextAttachment)
        )
        dataSource.saveLastOpenImageGenerationChat(
            conversation("last-image", lastImageUrl, lastImageAttachment)
        )

        val manager = DataPersistenceManager(
            context = context,
            stateHolder = ViewModelStateHolder(),
            viewModelScope = scope,
            imageLoader = ImageLoader.Builder(context).build(),
        )
        manager.cleanupOrphanedAttachments()

        listOf(
            textUrl,
            textAttachment,
            imageUrl,
            imageAttachment,
            lastTextUrl,
            lastTextAttachment,
            lastImageUrl,
            lastImageAttachment,
        ).forEach { referenced ->
            assertTrue("引用文件不应被删除：${referenced.name}", referenced.exists())
        }
        assertFalse(orphan.exists())
    }

    private fun conversation(id: String, image: File, attachment: File): List<Message> = listOf(
        Message(
            id = id,
            text = "消息 $id",
            sender = Sender.User,
            imageUrls = listOf(image.absolutePath),
            attachments = listOf(
                SelectedMediaItem.GenericFile(
                    uri = Uri.fromFile(attachment),
                    id = "attachment-$id",
                    displayName = attachment.name,
                    mimeType = "application/octet-stream",
                    filePath = attachment.absolutePath,
                )
            ),
        )
    )

    private fun createFile(directory: File, name: String): File =
        File(directory, name).apply { writeText(name) }
}
