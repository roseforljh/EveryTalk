package com.android.everytalk.ui.components.math

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MathRendererComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var renderer: MathJaxSvgRenderer

    @Before
    fun setUp() {
        renderer = MathJaxSvgRenderer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    @Test
    fun `新增公式不会让已完成公式重新进入Loading`() {
        val firstFormula = FormulaRequest(
            id = "a".repeat(64),
            latex = "",
            displayMode = FormulaDisplayMode.INLINE,
            contentVersion = 1L,
        )
        val secondFormula = FormulaRequest(
            id = "b".repeat(64),
            latex = "",
            displayMode = FormulaDisplayMode.BLOCK,
            contentVersion = 2L,
        )
        val initialState = AtomicReference<MathFormulaRenderState?>()
        val statesAfterAddition = Collections.synchronizedList(
            mutableListOf<MathFormulaRenderState?>()
        )
        lateinit var addSecondFormula: () -> Unit
        var captureAfterAddition = false

        composeRule.setContent {
            var formulas by remember {
                mutableStateOf(linkedMapOf(firstFormula.id to firstFormula))
            }
            addSecondFormula = {
                captureAfterAddition = true
                formulas = linkedMapOf(
                    firstFormula.id to firstFormula,
                    secondFormula.id to secondFormula,
                )
            }
            val states = rememberMathFormulaRenderStates(
                renderer = renderer,
                formulas = formulas,
                fontSizePx = 32f,
                color = "#000000",
                blockMaxWidthPx = 320f,
            )
            SideEffect {
                initialState.set(states[firstFormula.id])
                if (captureAfterAddition) {
                    statesAfterAddition.add(states[firstFormula.id])
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            initialState.get() is MathFormulaRenderState.Error
        }
        composeRule.runOnIdle { addSecondFormula() }
        composeRule.waitForIdle()

        assertFalse(
            statesAfterAddition.any { it == MathFormulaRenderState.Loading }
        )
    }
}
