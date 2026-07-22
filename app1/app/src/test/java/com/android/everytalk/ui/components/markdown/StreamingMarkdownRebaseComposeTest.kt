package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StreamingMarkdownRebaseComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `新流式状态准备完成后才交付给调用方`() {
        val content = "第一段\n\n公式占位\n\n第二段"
        val request = StreamingMarkdownRebaseRequest(
            generation = 1,
            content = content,
        )

        composeRule.setContent {
            PrepareStreamingMarkdownRebase(request)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            request.result.isCompleted
        }

        val state = runBlocking { request.result.await() }
        assertEquals(content, state.content.toString())
    }

    @Test
    fun `非追加重建期间正文与公式元数据始终来自同一代`() {
        val firstId = "a".repeat(64)
        val secondId = "b".repeat(64)
        val firstMarkdown = "第一版 ![x](${INLINE_FORMULA_SCHEME}$firstId)"
        val secondMarkdown = "第二版 ![y](${INLINE_FORMULA_SCHEME}$secondId)"
        val first = PreparedMessage(
            markdown = firstMarkdown,
            formulas = mapOf(
                firstId to FormulaRequest(firstId, "x", FormulaDisplayMode.INLINE, 1L)
            ),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val second = PreparedMessage(
            markdown = secondMarkdown,
            formulas = mapOf(
                secondId to FormulaRequest(secondId, "y", FormulaDisplayMode.INLINE, 2L)
            ),
            hasPendingFormula = false,
            contentVersion = 2L,
        )
        val current = mutableStateOf(first)
        val allowSecondRebase = CompletableDeferred<Unit>()
        val visiblePair = AtomicReference<Pair<String, String>?>(null)
        val mixedGenerationCount = AtomicInteger(0)

        composeRule.setContent {
            val bundle = rememberStreamingMarkdownBundle(
                preparedMessage = current.value,
                streamEpoch = 1,
                enabled = true,
                beforeRebase = { generation ->
                    if (generation == 2) allowSecondRebase.await()
                },
            )
            SideEffect {
                bundle?.let {
                    val pair = it.state.content.toString() to it.preparedMessage.markdown
                    if (pair.first != pair.second) mixedGenerationCount.incrementAndGet()
                    visiblePair.set(pair)
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            visiblePair.get() == (firstMarkdown to firstMarkdown)
        }

        composeRule.runOnIdle { current.value = second }
        composeRule.waitForIdle()
        assertEquals(firstMarkdown to firstMarkdown, visiblePair.get())

        allowSecondRebase.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            visiblePair.get() == (secondMarkdown to secondMarkdown)
        }
        assertEquals(0, mixedGenerationCount.get())
    }

    @Test
    fun `流式结束时最终内容在同一bundle内原子校准且不中断可见状态`() {
        val first = PreparedMessage(
            markdown = "第一段\n\n第二段",
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )
        val final = PreparedMessage(
            markdown = "第一段\n\n第二段\n\n第三段\n\n第四段",
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 2L,
        )
        val advanceToFinal = CompletableDeferred<Unit>()
        val finishStreaming = CompletableDeferred<Unit>()
        val visiblePair = AtomicReference<Pair<String, String>?>(null)
        val rebaseCount = AtomicInteger(0)
        val ready = AtomicBoolean(false)
        val observedNullAfterReady = AtomicBoolean(false)

        composeRule.setContent {
            val currentMessage = remember { mutableStateOf(first) }
            val streamingState = remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                advanceToFinal.await()
                currentMessage.value = final
                finishStreaming.await()
                streamingState.value = false
            }
            val bundle = rememberStreamingMarkdownBundle(
                preparedMessage = currentMessage.value,
                streamEpoch = 1,
                enabled = true,
                isStreaming = streamingState.value,
                beforeRebase = { rebaseCount.incrementAndGet() },
            )
            SideEffect {
                if (ready.get() && bundle == null) {
                    observedNullAfterReady.set(true)
                }
                bundle?.let {
                    visiblePair.set(it.state.content.toString() to it.preparedMessage.markdown)
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            visiblePair.get() == (first.markdown to first.markdown)
        }
        ready.set(true)

        advanceToFinal.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            visiblePair.get() == (final.markdown to final.markdown)
        }

        finishStreaming.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            visiblePair.get() == (final.markdown to final.markdown) && rebaseCount.get() >= 2
        }

        assertFalse(observedNullAfterReady.get())
        assertEquals(final.markdown to final.markdown, visiblePair.get())
    }
}
