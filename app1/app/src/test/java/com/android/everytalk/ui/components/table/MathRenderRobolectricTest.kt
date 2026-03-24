package com.android.everytalk.ui.components.table

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.ContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MathRenderRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pmatrix formula should be parsed as math and rendered in compose tree`() {
        val formula = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"
        val parts = com.android.everytalk.ui.components.ContentParser.parseCompleteContent(formula)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(MathRenderStrategy.shouldForceMarkdownRendererForMathPart(formula))

        composeRule.setContent {
            MaterialTheme {
                TableAwareText(
                    text = formula,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "math_container" }
                )
            }
        }

        composeRule.onNodeWithTag("math_container").assertExists()
        composeRule.onNodeWithText("A").assertExists()
        composeRule.onNodeWithText("D").assertExists()
    }

    @Test
    fun `bmatrix formula should be parsed as math and rendered in compose tree`() {
        val formula = "$$ M = \\begin{bmatrix} \\frac{\\partial^2 f}{\\partial x^2} & \\frac{\\partial^2 f}{\\partial x \\partial y} \\\\ \\frac{\\partial^2 f}{\\partial y \\partial x} & \\frac{\\partial^2 f}{\\partial y^2} \\end{bmatrix} $$"
        val parts = com.android.everytalk.ui.components.ContentParser.parseCompleteContent(formula)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(MathRenderStrategy.shouldForceMarkdownRendererForMathPart(formula))

        composeRule.setContent {
            MaterialTheme {
                TableAwareText(
                    text = formula,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "math_container" }
                )
            }
        }

        composeRule.onNodeWithTag("math_container").assertExists()
        composeRule.onNodeWithText("M").assertExists()
    }
}
