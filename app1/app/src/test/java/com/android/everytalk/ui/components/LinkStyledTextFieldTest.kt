package com.android.everytalk.ui.components

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.skill.MessageSkillReference
import com.android.everytalk.data.skill.SkillSourceType
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.SKILL_TAG_MARKER
import com.android.everytalk.ui.screens.MainScreen.chat.text.skill.SkillTagVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkStyledTextFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `多个网址即时着色并保留原文和全部光标位置`() {
        val raw = "看 https://github.com/a_(b)，再看 (https://unknown.example/path).\nhttps://youtu.be/test"
        val input = AnnotatedString(raw)
        val result = ComposerLinkVisualTransformation(color = Color.Blue).filter(input)
        val icons = result.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, result.text.length)
        assertEquals(listOf("https://github.com/a_(b)", "https://unknown.example/path", "https://youtu.be/test"), icons.map { it.item })
        assertEquals(raw, result.text.text.replace("\u2003\u2060", ""))
        assertEquals(raw, input.text)
        assertTrue(result.text.spanStyles.count { it.item.color == Color.Blue } == 3)
        for (offset in 0..raw.length) {
            val display = result.offsetMapping.originalToTransformed(offset)
            assertEquals(offset, result.offsetMapping.transformedToOriginal(display))
        }
        var previous = 0
        for (offset in 0..result.text.length) {
            val source = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(source in previous..raw.length)
            previous = source
        }
    }

    @Test
    fun `不完整网址和非网页协议保持普通文本`() {
        for (raw in listOf("https://", "https://?query", "mailto:test@example.com", "everytalk-footnote-reference:1")) {
            val result = ComposerLinkVisualTransformation(color = Color.Blue).filter(AnnotatedString(raw))
            assertEquals(raw, result.text.text)
            assertTrue(result.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, result.text.length).isEmpty())
        }
    }

    @Test
    fun `Skill标签与链接共同显示且标签名称不会生成额外网址`() {
        val reference = MessageSkillReference(
            skillId = "local:test",
            displayName = "https://skill.example",
            sourceType = SkillSourceType.USER_CREATED,
            contentHash = "hash",
        )
        val raw = "$SKILL_TAG_MARKER https://github.com 后续"
        val result = ComposerLinkVisualTransformation(
            SkillTagVisualTransformation(listOf(reference), Color.Cyan, Color.Gray),
            Color.Blue,
        ).filter(AnnotatedString(raw))
        assertTrue(result.text.text.startsWith("‹https://skill.example› "))
        assertEquals(1, result.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, result.text.length).size)
        for (offset in 0..raw.length) {
            assertEquals(offset, result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset)))
        }
    }

    @Test
    fun `输入粘贴修改和外部清空均保留原生草稿`() {
        val value = mutableStateOf(TextFieldValue(""))
        val dark = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                LinkStyledTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    modifier = Modifier.width(240.dp).testTag("editor"),
                )
            }
        }
        val editor = composeRule.onNodeWithTag("editor")
        editor.performClick().performTextInput("https://github.com")
        composeRule.runOnIdle { assertEquals("https://github.com", value.value.text) }
        val layouts = mutableListOf<TextLayoutResult>()
        editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("https://github.com", layouts.single().layoutInput.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, layouts.single().layoutInput.text.length).single().item)
        editor.performTextInput("/openai 后续")
        composeRule.runOnIdle { assertEquals("https://github.com/openai 后续", value.value.text) }
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(0, value.value.text.length, true) }
        editor.performSemanticsAction(SemanticsActions.CopyText) { it() }
        composeRule.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
        composeRule.runOnIdle { assertEquals(value.value.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        editor.performTextReplacement("https://unknown.example/path")
        composeRule.runOnIdle {
            assertEquals("https://unknown.example/path", value.value.text)
            dark.value = true
        }
        composeRule.waitForIdle()
        editor.performTextReplacement("https://")
        layouts.clear()
        editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, layouts.single().layoutInput.text.length).isEmpty())
        composeRule.runOnIdle { value.value = TextFieldValue("") }
        composeRule.waitForIdle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `深浅主题下真实绘制已知网站和地球图标`() {
        val href = mutableStateOf("https://github.com")
        val dark = mutableStateOf(false)
        var view: View? = null
        composeRule.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                LinkStyledTextField(
                    value = href.value,
                    onValueChange = { href.value = it },
                    modifier = Modifier.width(240.dp).background(if (dark.value) Color.Black else Color.White).testTag("editor"),
                )
            }
        }
        val editor = composeRule.onNodeWithTag("editor")
        for (isDark in listOf(false, true)) {
            for (site in listOf("github.com", "google.com", "unknown.example")) {
                composeRule.runOnIdle {
                    href.value = "https://$site"
                    dark.value = isDark
                }
                val layouts = mutableListOf<TextLayoutResult>()
                editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val result = layouts.single()
                val icon = result.layoutInput.text.getStringAnnotations(COMPOSER_LINK_ICON, 0, result.layoutInput.text.length).single()
                val bounds = result.getBoundingBox(icon.start)
                assertTrue("图标需要保留足够宽度", bounds.width > 10f)
                val editorBounds = editor.fetchSemanticsNode().boundsInRoot
                val background = if (isDark) 0f else 1f
                var drawn = 0
                composeRule.runOnIdle {
                    val root = requireNotNull(view)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    // Robolectric 的窗口 PixelCopy 无法回调，直接绘制同一原生 View 校验实际像素。
                    root.draw(Canvas(bitmap))
                    for (x in bounds.left.toInt() until (bounds.left + bounds.width * 16f / 19f).toInt()) {
                        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt()) {
                            val pixel = Color(bitmap.getPixel(x + editorBounds.left.toInt(), y + editorBounds.top.toInt()))
                            if (kotlin.math.abs(pixel.red - background) > 0.1f ||
                                kotlin.math.abs(pixel.green - background) > 0.1f ||
                                kotlin.math.abs(pixel.blue - background) > 0.1f) drawn++
                        }
                    }
                    bitmap.recycle()
                }
                assertTrue("$site 在深色=$isDark 时必须实际绘出图标", drawn > 5)
            }
        }
    }

    @Test
    fun `多行长网址滚动后仍可编辑且String入口保持原文`() {
        val raw = "前文\n".repeat(8) + "https://github.com/" + "long-path/".repeat(25)
        val value = mutableStateOf(raw)
        composeRule.setContent {
            MaterialTheme {
                LinkStyledTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    modifier = Modifier.width(200.dp).testTag("editor"),
                )
            }
        }
        val editor = composeRule.onNodeWithTag("editor")
        editor.performClick()
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(raw.length, raw.length, true) }
        editor.performTextInput("尾")
        composeRule.runOnIdle { assertEquals(raw + "尾", value.value) }
        editor.performTextReplacement("https://youtu.be/test")
        composeRule.runOnIdle { assertEquals("https://youtu.be/test", value.value) }
    }
}
