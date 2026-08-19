package com.android.everytalk.util.storage

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AudioAttachmentPersistenceTest {

    @Test
    fun `Base64音频持久化后只需保存文件路径`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = "voice-content".encodeToByteArray()
        val path = FileManager(context).persistBase64Attachment(
            base64Data = Base64.encodeToString(source, Base64.NO_WRAP),
            mimeType = "audio/mp4",
            messageIdHint = "message",
            attachmentIndex = 0,
        )

        assertNotNull(path)
        val persisted = File(path!!)
        assertTrue(persisted.isFile)
        assertArrayEquals(source, persisted.readBytes())
        persisted.delete()
        Unit
    }
}
