package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AttachmentLayoutRulesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `user image attachments are anchored to the right`() {
        assertEquals(Alignment.End, attachmentStripHorizontalAlignment(Sender.User))
    }

    @Test
    fun `ai image attachments keep left alignment`() {
        assertEquals(Alignment.Start, attachmentStripHorizontalAlignment(Sender.AI))
    }

    @Test
    fun `generated landscape image keeps original ratio in conversation`() {
        assertGeneratedImageRatio(160, 90)
    }

    @Test
    fun `generated portrait image keeps original ratio in conversation`() {
        assertGeneratedImageRatio(90, 160)
    }

    /** 测试加载器提供已解码图片，直接测量对话组件布局；文件解码和保存由持久化测试覆盖。 */
    @OptIn(DelicateCoilApi::class)
    private fun assertGeneratedImageRatio(width: Int, height: Int) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val loader = ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        val loaded = AtomicBoolean(false)
        try {
            composeRule.setContent {
                MaterialTheme {
                    // 与真实对话列表相同，图片项允许高于一屏，不能用普通 Box 的屏高截断竖图。
                    LazyColumn(Modifier.width(300.dp)) {
                        item {
                            AttachmentsContent(
                                attachments = listOf(SelectedMediaItem.ImageFromUri(Uri.parse("file:///test-image.png"), "test-image")),
                                onAttachmentClick = {},
                                maxWidth = 300.dp,
                                message = Message(text = "", sender = Sender.AI),
                                onEditRequest = {},
                                onRegenerateRequest = {},
                                onLongPress = { _, _ -> },
                                onImageLoaded = { loaded.set(true) },
                                scrollStateManager = mockk(),
                                isAiGenerated = true,
                            )
                        }
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 10_000) { loaded.get() }
            val bounds = composeRule.onNodeWithContentDescription(
                context.getString(R.string.attachment_generated_image),
                useUnmergedTree = true,
            ).fetchSemanticsNode().layoutInfo
            assertEquals(width.toFloat() / height, bounds.width.toFloat() / bounds.height, 0.03f)
        } finally {
            SingletonImageLoader.reset()
            loader.shutdown()
        }
    }
}
