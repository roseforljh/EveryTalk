package com.android.everytalk.ui.components.table

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.markdown.BreakableLatexDrawable
import com.android.everytalk.ui.components.markdown.NativeLatexSupport
import com.android.everytalk.ui.components.markdown.StableLatexDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

private fun SemanticsNodeInteraction.assertExistsCompat(): SemanticsNodeInteraction {
    fetchSemanticsNode()
    return this
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MathRenderRobolectricTest {

    private fun countNonTransparentPixels(bitmap: Bitmap): Int {
        var nonTransparent = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) != 0) {
                    nonTransparent++
                }
            }
        }
        return nonTransparent
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        stopKoin()
    }

    @Test
    fun `pmatrix formula should be parsed as math and rendered in compose tree`() {
        val formula = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"
        val parts = com.android.everytalk.ui.components.ContentParser.parseCompleteContent(formula)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(MathRenderStrategy.shouldForceNativeBlockRendererForMathPart(formula))

        composeRule.setContent {
            MaterialTheme {
                TableAwareText(
                    text = formula,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "math_container" }
                )
            }
        }

        composeRule.onNode(hasTestTag("math_container")).assertExistsCompat()
    }

    @Test
    fun `bmatrix formula should be parsed as math and rendered in compose tree`() {
        val formula = "$$ M = ${'\\'}begin{bmatrix} a & b ${'\\'}${'\\'} c & d ${'\\'}end{bmatrix} $$"
        val parts = com.android.everytalk.ui.components.ContentParser.parseCompleteContent(formula)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(MathRenderStrategy.shouldForceNativeBlockRendererForMathPart(formula))

        composeRule.setContent {
            MaterialTheme {
                TableAwareText(
                    text = formula,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "math_container" }
                )
            }
        }

        composeRule.onNode(hasTestTag("math_container")).assertExistsCompat()
    }

    @Test
    fun `pmatrix formula should build native icon instead of relying on raw markdown text`() {
        val formula = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"

        var intrinsicWidth = 0
        var intrinsicHeight = 0
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            intrinsicWidth = icon.iconWidth
            intrinsicHeight = icon.iconHeight
        }

        composeRule.runOnIdle {
            assertTrue(intrinsicWidth > 0)
            assertTrue(intrinsicHeight > 0)
        }
    }

    @Test
    fun `pmatrix formula should build native icon with positive size`() {
        val formula = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"

        var iconWidth = 0
        var iconHeight = 0
        var canRenderNatively = false
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            canRenderNatively = NativeLatexSupport.canRenderNatively(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            iconWidth = icon.iconWidth
            iconHeight = icon.iconHeight
        }

        composeRule.runOnIdle {
            assertTrue(canRenderNatively)
            assertTrue(iconWidth > 0)
            assertTrue(iconHeight > 0)
        }
    }

    @Test
    fun `normalized pmatrix formula should keep native icon size positive`() {
        val formula = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"
        val normalizedFormula = NativeLatexSupport.normalizeForNativeBlockRenderer(formula)

        var iconWidth = 0
        var iconHeight = 0
        var canRenderNatively = false
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            canRenderNatively = NativeLatexSupport.canRenderNatively(
                latex = normalizedFormula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = normalizedFormula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            iconWidth = icon.iconWidth
            iconHeight = icon.iconHeight
        }

        composeRule.runOnIdle {
            assertTrue(canRenderNatively)
            assertTrue(iconWidth > 0)
            assertTrue(iconHeight > 0)
        }
    }

    @Test
    fun `complex bmatrix formula should build native icon instead of showing raw latex text`() {
        val formula = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        var canRenderNatively = false
        var intrinsicWidth = 0
        var intrinsicHeight = 0
        var markdownRenderedText = ""
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            canRenderNatively = NativeLatexSupport.canRenderNatively(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            intrinsicWidth = icon.iconWidth
            intrinsicHeight = icon.iconHeight
            markdownRenderedText = com.android.everytalk.ui.components.markdown.preprocessAiMarkdown(formula, isStreaming = false)
        }

        composeRule.runOnIdle {
            assertTrue(canRenderNatively)
            assertTrue(intrinsicWidth > 0)
            assertTrue(intrinsicHeight > 0)
            assertTrue(markdownRenderedText.contains("\\begin{bmatrix}"))
        }
    }

    @Test
    fun `complex matrix drawable should report positive intrinsic size`() {
        val formula = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        var drawable: BreakableLatexDrawable? = null
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            drawable = BreakableLatexDrawable.create(
                latex = NativeLatexSupport.extractPureMathContent(formula),
                textSize = 48f,
                color = android.graphics.Color.WHITE,
                maxWidthPx = 900f,
            )
        }

        composeRule.runOnIdle {
            assertNotNull(drawable)
            assertTrue(drawable!!.intrinsicWidth > 0)
            assertTrue(drawable!!.intrinsicHeight > 0)
        }
    }

    @Test
    fun `complex bmatrix stable drawable should draw visible pixels into bitmap when bounds are offset`() {
        val formula = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        var drawable: StableLatexDrawable? = null
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            drawable = StableLatexDrawable(icon)
        }

        composeRule.runOnIdle {
            assertNotNull(drawable)
            val stableDrawable = drawable!!
            val bitmap = Bitmap.createBitmap(
                stableDrawable.intrinsicWidth + 32,
                stableDrawable.intrinsicHeight + 32,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            stableDrawable.setBounds(16, 16, 16 + stableDrawable.intrinsicWidth, 16 + stableDrawable.intrinsicHeight)
            stableDrawable.draw(canvas)

            assertTrue(countNonTransparentPixels(bitmap) > 0)
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
        }
    }

    @Test
    fun `simple bmatrix formula should build native icon with positive size`() {
        val formula = "$$ M = \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix} $$"

        var iconWidth = 0
        var iconHeight = 0
        var canRenderNatively = false
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            canRenderNatively = NativeLatexSupport.canRenderNatively(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = formula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            iconWidth = icon.iconWidth
            iconHeight = icon.iconHeight
        }

        composeRule.runOnIdle {
            assertTrue(canRenderNatively)
            assertTrue(iconWidth > 0)
            assertTrue(iconHeight > 0)
        }
    }

    @Test
    fun `normalized bmatrix formula should keep native icon size positive`() {
        val formula = "$$ M = \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix} $$"
        val normalizedFormula = NativeLatexSupport.normalizeForNativeBlockRenderer(formula)

        var iconWidth = 0
        var iconHeight = 0
        var canRenderNatively = false
        composeRule.setContent {
            val context = LocalContext.current
            NativeLatexSupport.ensureInitialized(context)
            canRenderNatively = NativeLatexSupport.canRenderNatively(
                latex = normalizedFormula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            val icon = NativeLatexSupport.buildDisplayIcon(
                latex = normalizedFormula,
                textSizePx = 48f,
                colorArgb = android.graphics.Color.WHITE
            )
            iconWidth = icon.iconWidth
            iconHeight = icon.iconHeight
        }

        composeRule.runOnIdle {
            assertTrue(canRenderNatively)
            assertTrue(iconWidth > 0)
            assertTrue(iconHeight > 0)
        }
    }
}
