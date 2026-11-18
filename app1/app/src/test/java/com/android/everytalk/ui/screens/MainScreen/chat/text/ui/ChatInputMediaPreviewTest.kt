package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.R
import com.android.everytalk.models.SelectedMediaItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatInputMediaPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `输入框为空开始追加同地址的两张图后点第二张预览从第二张打开`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val media = mutableStateListOf<SelectedMediaItem>()
        compose.setContent { MaterialTheme { OptimizedMediaItemsList(media, { media.removeAt(it) }) } }
        compose.runOnIdle {
            val uri = Uri.parse("content://test/gallery.jpg")
            media.add(SelectedMediaItem.GenericFile(uri, "first", "first.jpg", "image/jpeg"))
            media.add(SelectedMediaItem.GenericFile(uri, "second", "second.jpg", "image/jpeg"))
        }
        compose.onNodeWithContentDescription("second.jpg").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.image_preview_close)).assertIsDisplayed()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
    }
}
