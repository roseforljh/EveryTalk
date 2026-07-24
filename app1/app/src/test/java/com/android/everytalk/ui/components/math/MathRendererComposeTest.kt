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
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                mutableStateOf(mapOf(firstFormula.id to firstFormula))
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

    @Test
    fun `公式离屏后重新组合直接复用内存Ready状态`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val formula = FormulaRequest(
            id = "cached-reentry-" + "c".repeat(64),
            latex = "x^2",
            displayMode = FormulaDisplayMode.INLINE,
            contentVersion = 1L,
        )
        val request = MathJaxRenderRequest(
            id = formula.id,
            latex = formula.latex,
            display = false,
            fontSizePx = 32f,
            color = "#000000",
            requestVersion = mathFormulaRequestVersion(formula),
        )
        val result = MathJaxRenderResult(
            id = request.id,
            status = MathJaxRenderStatus.READY,
            svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 10\"><path d=\"M0 0h20v10z\"/></svg>",
            widthPx = 20f,
            heightPx = 10f,
            depthPx = 2f,
            viewBox = "0 0 20 10",
            requestVersion = request.requestVersion,
        )
        val cacheRoot = application.cacheDir
        MathFormulaDiskCache(
            File(cacheRoot, MathFormulaDiskCache.DIRECTORY_NAME)
        ).write(cacheKeyOf(request), result)
        runBlocking {
            val primed = MathFormulaSvgCache.render(cacheRoot, renderer, listOf(request))
            assertTrue(primed.single().second.status == MathJaxRenderStatus.READY)
        }
        assertTrue(
            MathFormulaSvgCache.getMemoryReadyResults(listOf(request))
                .containsKey(cacheKeyOf(request))
        )

        val statesAfterReentry = ConcurrentLinkedQueue<MathFormulaRenderState?>()
        val formulaVisible = mutableStateOf(true)
        val captureAfterReentry = AtomicBoolean(false)
        composeRule.setContent {
            if (formulaVisible.value) {
                val states = rememberMathFormulaRenderStates(
                    renderer = renderer,
                    formulas = mapOf(formula.id to formula),
                    fontSizePx = request.fontSizePx,
                    color = request.color,
                    blockMaxWidthPx = 320f,
                )
                SideEffect {
                    if (captureAfterReentry.get()) {
                        statesAfterReentry.add(states[formula.id])
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { formulaVisible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            captureAfterReentry.set(true)
            formulaVisible.value = true
        }
        composeRule.waitForIdle()

        assertTrue("重入后未采集到公式状态：$statesAfterReentry", statesAfterReentry.isNotEmpty())
        assertFalse(statesAfterReentry.any { it == MathFormulaRenderState.Loading })
        assertTrue(statesAfterReentry.first() is MathFormulaRenderState.Ready)
    }
}
