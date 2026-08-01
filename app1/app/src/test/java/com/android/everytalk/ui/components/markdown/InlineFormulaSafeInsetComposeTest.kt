package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.math.MathFormulaRenderState
import com.android.everytalk.ui.components.math.MathJaxRenderRequest
import com.android.everytalk.ui.components.math.MathJaxRenderResult
import com.android.everytalk.ui.components.math.MathJaxRenderStatus
import com.android.everytalk.ui.components.math.cacheKeyOf
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InlineFormulaSafeInsetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `SVG保持原始高度并居中于带安全区的占位框`() {
        val id = "a".repeat(64)
        val formula = FormulaRequest(id, "x", FormulaDisplayMode.INLINE, 1L)
        val request = MathJaxRenderRequest(
            id = id,
            latex = formula.latex,
            display = false,
            fontSizePx = 10f,
            color = "#000000",
        )
        val state = MathFormulaRenderState.Ready(
            result = MathJaxRenderResult(
                id = id,
                status = MathJaxRenderStatus.READY,
                svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                    "viewBox=\"0 0 20 10\"><path d=\"M0 0h20v10H0z\"/></svg>",
                widthPx = 20f,
                heightPx = 10f,
            ),
            cacheKey = cacheKeyOf(request),
        )
        val metrics = inlineFormulaMetrics(state, request.fontSizePx)

        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(45.dp)
                        .testTag("公式占位框"),
                ) {
                    InlineFormulaContent(
                        formula = formula,
                        state = state,
                        metrics = metrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val placeholderBounds = composeRule
            .onNodeWithTag("公式占位框")
            .fetchSemanticsNode("")
            .boundsInRoot
        val formulaBounds = composeRule
            .onNodeWithContentDescription("数学公式：x", useUnmergedTree = true)
            .fetchSemanticsNode("")
            .boundsInRoot
        val topInset = formulaBounds.top - placeholderBounds.top
        val bottomInset = placeholderBounds.bottom - formulaBounds.bottom

        assertEquals(placeholderBounds.width, formulaBounds.width, 0.5f)
        assertTrue(formulaBounds.height < placeholderBounds.height)
        assertTrue(topInset > 0f)
        assertEquals(topInset, bottomInset, 1.1f)
    }
}
