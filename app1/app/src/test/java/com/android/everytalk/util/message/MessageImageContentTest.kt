package com.android.everytalk.util.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageImageContentTest {

    @Test
    fun `外部传输文本移除 Markdown Base64 图片并保留正文`() {
        val source = "生成结果如下：\n\n![Generated Image](data:image/png;base64,QUJDRA==)\n\n处理完成。"

        val result = prepareTextForExternalTransfer(source)

        assertEquals("生成结果如下：\n\n处理完成。", result)
        assertFalse(result.contains("data:image", ignoreCase = true))
    }

    @Test
    fun `外部传输文本移除裸 Base64 图片载荷`() {
        val source = "图片数据：data:image/jpeg;base64,QUJDREVGRw=="

        val result = prepareTextForExternalTransfer(source)

        assertEquals("图片数据：", result)
        assertFalse(result.contains("data:image", ignoreCase = true))
    }

    @Test
    fun `外部传输文本按 UTF8 字节安全截断`() {
        val source = "🙂中文".repeat(100_000)

        val result = prepareTextForExternalTransfer(source)

        assertTrue(result.endsWith("[内容过长，已截断]"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MAX_EXTERNAL_TRANSFER_BYTES)
        assertFalse(result.last().isHighSurrogate())
    }

    @Test
    fun `文本导出移除图片但不截断长正文`() {
        val longText = "正文".repeat(200_000)
        val source = "$longText\n![图](data:image/png;base64,QUJD)"

        val result = prepareTextForExport(source)

        assertEquals(longText, result)
        assertFalse(result.contains("data:image", ignoreCase = true))
    }

    @Test
    fun `外部传输文本移除带换行的 Base64 载荷`() {
        val source = "正文\ndata:image/png;base64,QUJD\nREVGRw==\n结束"

        val result = prepareTextForExternalTransfer(source)

        assertEquals("正文\n结束", result)
        assertFalse(result.contains("data:image", ignoreCase = true))
    }

    @Test
    fun `裸 Base64 填充结束后保留英文正文`() {
        val source = "data:image/png;base64,QUJDRA==\nDone"

        val result = prepareTextForExternalTransfer(source)

        assertEquals("Done", result)
    }

    @Test
    fun `文本净化保留普通 Markdown 链接`() {
        val source = "查看 [EveryTalk](https://github.com/roseforljh/EveryTalk)"

        val result = prepareTextForExternalTransfer(source)

        assertEquals(source, result)
    }

    @Test
    fun `纯图片消息净化后返回短占位`() {
        val source = "![生成图](data:image/png;base64,QUJDRA==)"

        val result = prepareTextForExternalTransfer(source)

        assertEquals("[图片内容已省略]", result)
    }

    @Test
    fun `大体积裸 Base64 在复制前单次扫描移除`() {
        val source = "data:image/png;base64," + "A".repeat(5 * 1024 * 1024) + "==\nDone"

        val result = prepareTextForExternalTransfer(source)

        assertEquals("Done", result)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MAX_EXTERNAL_TRANSFER_BYTES)
    }
}
