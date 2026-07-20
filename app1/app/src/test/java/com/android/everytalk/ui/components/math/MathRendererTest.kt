package com.android.everytalk.ui.components.math

import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MathRendererTest {

    @Test
    fun `公式请求版本只随公式身份变化`() {
        val formula = FormulaRequest(
            id = "a".repeat(64),
            latex = "x^2",
            displayMode = FormulaDisplayMode.INLINE,
            contentVersion = 1L,
        )

        assertEquals(
            mathFormulaRequestVersion(formula),
            mathFormulaRequestVersion(formula.copy(contentVersion = 99L)),
        )
        assertNotEquals(
            mathFormulaRequestVersion(formula),
            mathFormulaRequestVersion(formula.copy(id = "b".repeat(64))),
        )
    }

    @Test
    fun `公式领域对象保留内容版本而渲染身份忽略内容版本`() {
        val first = FormulaRequest(
            id = "a".repeat(64),
            latex = "x^2",
            displayMode = FormulaDisplayMode.INLINE,
            contentVersion = 1L,
        )
        val second = first.copy(contentVersion = 99L)

        assertNotEquals(first, second)
        assertEquals(first.renderIdentity(), second.renderIdentity())
        assertEquals(
            formulaRenderIdentities(mapOf(first.id to first)),
            formulaRenderIdentities(mapOf(second.id to second)),
        )
    }

    @Test
    fun `新增公式只进入Loading并保留旧公式Ready和Error状态`() {
        val readyRequest = MathJaxRenderRequest(
            id = "a".repeat(64),
            latex = "x^2",
            display = false,
            fontSizePx = 32f,
            color = "#112233",
        )
        val errorRequest = readyRequest.copy(
            id = "b".repeat(64),
            latex = "y^2",
        )
        val addedRequest = readyRequest.copy(
            id = "c".repeat(64),
            latex = "z^2",
        )
        val readyState = MathFormulaRenderState.Ready(
            result = MathJaxRenderResult(
                id = readyRequest.id,
                status = MathJaxRenderStatus.READY,
                svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 10\"><path d=\"M0 0h20v10z\"/></svg>",
                widthPx = 20f,
                heightPx = 10f,
                depthPx = 2f,
            ),
            cacheKey = cacheKeyOf(readyRequest),
        )
        val errorState = MathFormulaRenderState.Error(MathFormulaErrorKind.SYNTAX)
        val previous = MathFormulaRenderSnapshot(
            requestsById = mapOf(
                readyRequest.id to readyRequest,
                errorRequest.id to errorRequest,
            ),
            statesById = mapOf(
                readyRequest.id to readyState,
                errorRequest.id to errorState,
            ),
        )

        val reconciled = reconcileMathFormulaRenderStates(
            previous = previous,
            requests = listOf(readyRequest, errorRequest, addedRequest),
        )

        assertSame(readyState, reconciled[readyRequest.id])
        assertSame(errorState, reconciled[errorRequest.id])
        assertSame(MathFormulaRenderState.Loading, reconciled[addedRequest.id])
        assertSame(
            MathFormulaRenderState.Loading,
            reconcileMathFormulaRenderStates(
                previous = previous,
                requests = listOf(readyRequest.copy(color = "#ffffff")),
            )[readyRequest.id],
        )
    }

    @Test
    fun `SVG缓存键忽略内容版本并包含全部渲染配置`() {
        val base = MathJaxRenderRequest(
            id = "a".repeat(64),
            latex = "x^2",
            display = false,
            fontSizePx = 32f,
            color = "#112233",
            maxWidthPx = null,
            requestVersion = 1L,
        )

        assertEquals(cacheKeyOf(base), cacheKeyOf(base.copy(requestVersion = 99L)))
        assertNotEquals(cacheKeyOf(base), cacheKeyOf(base.copy(display = true)))
        assertNotEquals(cacheKeyOf(base), cacheKeyOf(base.copy(fontSizePx = 33f)))
        assertNotEquals(cacheKeyOf(base), cacheKeyOf(base.copy(color = "#ffffff")))
        assertNotEquals(cacheKeyOf(base), cacheKeyOf(base.copy(maxWidthPx = 320f)))
        assertEquals(MathJaxSvgRenderer.MATHJAX_VERSION, cacheKeyOf(base).mathJaxVersion)
        assertEquals(MathJaxSvgRenderer.MATHJAX_CONFIG_HASH, cacheKeyOf(base).mathJaxConfigHash)

        val coilKey = cacheKeyOf(base).coilMemoryCacheKey()
        assertTrue(coilKey.contains(base.id))
        assertTrue(coilKey.contains(base.fontSizePx.toRawBits().toString()))
        assertTrue(coilKey.contains(base.color))
        assertTrue(coilKey.contains(MathJaxSvgRenderer.MATHJAX_VERSION))
        assertTrue(coilKey.endsWith(MathJaxSvgRenderer.MATHJAX_CONFIG_HASH))
        assertEquals(
            coilKey,
            cacheKeyOf(base.copy(requestVersion = 99L)).coilMemoryCacheKey(),
        )
        assertNotEquals(coilKey, cacheKeyOf(base.copy(display = true)).coilMemoryCacheKey())
    }

    @Test
    fun `MathJax颜色使用稳定的小写CSS十六进制`() {
        assertEquals("#336699", androidx.compose.ui.graphics.Color(0xFF336699).toMathJaxCssColor())
        assertTrue(androidx.compose.ui.graphics.Color(0x80336699).toMathJaxCssColor().matches(Regex("#[0-9a-f]{8}")))
    }

    @Test
    fun `块公式宽度按32像素分桶`() {
        assertEquals(320f, mathWidthBucketPx(319f))
        assertEquals(288f, mathWidthBucketPx(300f))
        assertEquals(32f, mathWidthBucketPx(1f))
        assertEquals(null, mathWidthBucketPx(null))
        assertEquals(null, mathWidthBucketPx(Float.NaN))
    }

    @Test
    fun `SVG安全校验拒绝脚本外部资源事件属性和超量节点`() {
        val valid = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
              <defs><path id="glyph" d="M0 0h1v1z"/></defs>
              <use href="#glyph" data-latex="\text{https://example.com}"/>
            </svg>
        """.trimIndent()

        assertTrue(isSafeMathSvg(valid))
        assertFalse(isSafeMathSvg("<svg><script>alert(1)</script></svg>"))
        assertFalse(isSafeMathSvg("<svg><use href=\"https://example.com/a.svg#x\"/></svg>"))
        assertFalse(isSafeMathSvg("<svg><path fill=\"url(https://example.com/a.svg#x)\"/></svg>"))
        assertFalse(isSafeMathSvg("<svg><g onclick=\"alert(1)\"/></svg>"))
        assertFalse(isSafeMathSvg("<svg><foreignObject/></svg>"))
        assertFalse(
            isSafeMathSvg(
                "<svg>" + "<g/>".repeat(MAX_MATH_SVG_NODES) + "</svg>"
            )
        )
    }

    @Test
    fun `磁盘缓存保存SVG元数据并恢复当前内容版本`() {
        val directory = Files.createTempDirectory("everytalk-math-cache-test").toFile()
        try {
            val request = MathJaxRenderRequest(
                id = "b".repeat(64),
                latex = "x^2",
                display = false,
                fontSizePx = 32f,
                color = "#112233",
                requestVersion = 7L,
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
            val cache = MathFormulaDiskCache(directory, maxBytes = 1024L * 1024L)
            val key = cacheKeyOf(request)

            cache.write(key, result)
            val restored = cache.read(key, request.copy(requestVersion = 99L))

            assertNotNull(restored)
            assertEquals(99L, restored?.requestVersion)
            assertEquals(result.svg, restored?.svg)
            assertEquals(result.widthPx, restored?.widthPx)
            assertEquals(2, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
